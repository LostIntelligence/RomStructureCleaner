import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

public class RomCleaner {

    static Config config;
    static BufferedWriter log;
    static Scanner in = new Scanner(System.in);

    // Multi-file game subfolders per RomM spec
    static final Set<String> multiFileFolders = Set.of(
            "dlc", "hack", "manual", "mod", "patch", "update", "demo", "translation", "prototype");
    static final Set<Path> scheduledFiles = new LinkedHashSet<>();
    static final Set<Path> scheduledDirs = new LinkedHashSet<>();

    public static void main(String[] args) throws Exception {
        config = new ObjectMapper().readValue(new File("config.json"), Config.class);
        log = Files.newBufferedWriter(Paths.get(config.logFile),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        Path root = Paths.get(config.rootPath);

        log("====Starting System Processing====");
        try (var stream = Files.list(root)) {
            for (Path dir : stream.filter(Files::isDirectory).toList()) {
                processSystemFolder(dir);
            }
        }
        log("====Executing Scheduled Deletes====");
        executeScheduledDeletes();

        log("====Final Empty Folder Cleanup====");
        cleanupEmptyDirectories(root);

        log("====Ending Script====");
        log.close();
    }

    static void processSystemFolder(Path dir) throws IOException {
        // Step 1: normalize folder name (case handling)
        Path normalized = applyLowercaseRule(dir);

        if (isSingleTxtSystemFolder(normalized) || isEmptySystem(normalized)
                || systemOnlyContainsEmptyFolders(normalized)) {
            scheduleDelete(normalized, "Empty or single .txt system folder");
            return;
        }

        String folderName = normalized.getFileName().toString().toLowerCase();
        String resolvedKey = resolveSystemKey(folderName);

        SystemConfig sys = config.systems.get(resolvedKey);

        if (sys == null) {
            tagUnknownSystem(normalized);
            return;
        }

        normalized = tagAliasedSystemIfNeeded(normalized, resolvedKey);
        final Path systemDir = normalized;
        Files.walkFileTree(systemDir, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (!dir.equals(systemDir) && isStructuredGameFolder(dir, sys)) {
                    log("Skipping structured multi-file game folder: " + dir);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String ext = ext(file);

                // ZIPs are always handled, regardless of location
                if (ext.equals("zip")) {
                    handleZip(file, systemDir, sys);
                    return FileVisitResult.CONTINUE;
                }

                if (ext.isEmpty()) {
                    handleNoExtension(file);
                } else if (!sys.extensions.contains(ext)) {
                    scheduleDelete(file, "Invalid extension");
                } else {
                    moveRom(file, systemDir, sys);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                if (!d.equals(systemDir)
                        && isEmpty(d)
                        && !directoryContainsZip(d)) {
                    scheduleDelete(d, "Empty directory");
                }

                return FileVisitResult.CONTINUE;
            }
        });
    }

    // ===== Multi-file game detection =====
    static boolean isStructuredGameFolder(Path folder, SystemConfig sys) throws IOException {
        if (!Files.isDirectory(folder))
            return false;

        boolean hasSubfolders = false;
        boolean hasRootRom = false;

        try (DirectoryStream<Path> ds = Files.newDirectoryStream(folder)) {
            for (Path p : ds) {
                String name = p.getFileName().toString().toLowerCase();
                if (Files.isDirectory(p) && multiFileFolders.contains(name)) {
                    hasSubfolders = true;
                }
                if (Files.isRegularFile(p) && sys.extensions.contains(ext(p))) {
                    hasRootRom = true;
                }
            }
        }

        return hasSubfolders && hasRootRom;
    }

    // ===== ZIP HANDLING =====
    static void handleZip(Path zip, Path target, SystemConfig sys) throws IOException {
        boolean deleteZip = false;
        String deleteReason = null;

        try (ZipFile zf = new ZipFile(zip.toFile())) {

            List<ZipEntry> roms = new ArrayList<>();
            List<ZipEntry> junk = new ArrayList<>();

            for (ZipEntry e : Collections.list(zf.entries())) {
                if (e.isDirectory())
                    continue;
                if (sys.extensions.contains(ext(e.getName())))
                    roms.add(e);
                else
                    junk.add(e);
            }

            if (roms.isEmpty()) {
                deleteZip = true;
                deleteReason = "ZIP contained no valid ROMs";
            } else {
                switch (sys.zipPolicy) {
                    case "forbid" -> {
                        extract(zf, roms, target, sys);
                        deleteZip = true;
                        deleteReason = "ZIP forbidden";
                    }
                    case "extract" -> {
                        extract(zf, roms, target, sys);
                        deleteZip = true;
                        deleteReason = "ZIP extracted";
                    }
                    case "allow" -> {
                        if (!junk.isEmpty() || roms.size() > 1) {
                            promptZipDecision(zip, zf, roms, target, sys);
                        }
                    }
                }
            }

        } catch (ZipException e) {
            if (sys.zipPolicy.equals("forbid") || sys.zipPolicy.equals("extract")) {
                deleteZip = true;
                deleteReason = "Invalid ZIP (policy forbids ZIP)";
            } else {
                log("Corrupt or invalid ZIP skipped: " + zip + " (" + e.getMessage() + ")");
            }
        }

        // ZIP FILE IS CLOSED HERE — SAFE TO DELETE
        if (deleteZip) {
            scheduleDelete(zip, deleteReason);
        }
    }

    static void promptZipDecision(Path zip, ZipFile zf, List<ZipEntry> roms,
            Path target, SystemConfig sys) throws IOException {
        System.out.println("\nZIP ambiguity: " + zip);
        System.out.print("[E]xtract / [K]eep / [D]elete: ");
        String c = in.nextLine().toLowerCase();
        if (c.equals("e"))
            extract(zf, roms, target, sys);
        if (c.equals("d"))
            scheduleDelete(zip, "User deleted ZIP");
    }

    static void extract(ZipFile zf, List<ZipEntry> roms,
            Path target, SystemConfig sys) throws IOException {

        for (ZipEntry e : roms) {
            String clean = cleanName(e.getName(), sys);
            Path canonicalOut = target.resolve(clean);

            // Duplicate detection BEFORE resolveDup
            if (Files.exists(canonicalOut)) {
                if (sameSize(e, canonicalOut)) {
                    log("ZIP ROM duplicate skipped: " + canonicalOut);
                    return; // single-ROM ZIP → safe to stop
                }

                // Same name, different ROM → keep both
                Path deduped = resolveDup(canonicalOut);
                try (InputStream is = zf.getInputStream(e)) {
                    Files.copy(is, deduped);
                }
                log("Extracted (name collision): " + deduped);
                return;
            }

            // Normal extraction
            try (InputStream is = zf.getInputStream(e)) {
                Files.copy(is, canonicalOut);
            }
            log("Extracted: " + canonicalOut);
            return;
        }
    }

    // ===== ROM HANDLING =====
    static void moveRom(Path file, Path target, SystemConfig sys) throws IOException {
        String name = sys.rommNaming
                ? cleanName(file.getFileName().toString(), sys)
                : file.getFileName().toString();

        Path canonicalOut = target.resolve(name);

        // Already correct location
        if (file.normalize().equals(canonicalOut.normalize())) {
            return;
        }

        if (Files.exists(canonicalOut)) {
            if (isSameFile(file, canonicalOut)) {
                scheduleDelete(file, "Duplicate ROM (same size)");
                return;
            }

            // Same name, different content → keep both
            Path dedupedOut = resolveDup(canonicalOut);
            if (!config.dryRun)
                Files.move(file, dedupedOut);
            log("Moved (name collision): " + file + " → " + dedupedOut);
            return;
        }

        // Normal move
        if (!config.dryRun)
            Files.move(file, canonicalOut);
        log("Moved: " + file + " → " + canonicalOut);
    }

    static boolean isSameFile(Path a, Path b) throws IOException {
        if (!Files.exists(b))
            return false;
        return Files.size(a) == Files.size(b);
    }

    static boolean sameSize(ZipEntry e, Path existing) throws IOException {
        if (!Files.exists(existing))
            return false;
        return e.getSize() == Files.size(existing);
    }

    static String cleanName(String name, SystemConfig sys) {
        int dot = name.lastIndexOf('.');
        String base = name.substring(0, dot)
                .replace('_', ' ')
                .replaceAll("\\[.*?]", "")
                .replaceAll("\\s+", " ")
                .trim();
        return titleCase(base) + name.substring(dot).toLowerCase();
    }

    // ===== UTIL =====
    static void handleNoExtension(Path f) throws IOException {
        System.out.println("\nNo extension: " + f);
        System.out.print("[R]ename / [D]elete / [S]kip: ");
        String c = in.nextLine().toLowerCase();
        if (c.equals("d"))
            scheduleDelete(f, "User deleted");
        if (c.equals("r")) {
            System.out.print("Extension: ");
            Files.move(f, f.resolveSibling(f.getFileName() + "." + in.nextLine()));
        }
    }

    static void tagUnknownSystem(Path dir) throws IOException {
        if (dir.getFileName().toString().toLowerCase().endsWith(config.unknownSystemTag.toLowerCase()))
            return;
        Path tagged = dir.resolveSibling(dir.getFileName() + config.unknownSystemTag);
        if (!config.dryRun)
            Files.move(dir, tagged);
        log("Tagged unknown system: " + dir + " → " + tagged);
    }

    static boolean isEmpty(Path d) throws IOException {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(d)) {
            return !ds.iterator().hasNext();
        }
    }

    static void executeScheduledDeletes() throws IOException {

        // 1) Delete files first
        for (Path p : scheduledFiles) {
            tryDelete(p);
        }

        // 2) Collect all directories to delete (materialize first!)
        List<Path> dirs = new ArrayList<>(scheduledDirs);

        // Deepest paths first
        dirs.sort(Comparator.comparingInt(Path::getNameCount).reversed());

        for (Path dir : dirs) {
            deleteDirectoryTree(dir);
        }
    }

    static void tryDelete(Path p) {
        try {
            if (!config.dryRun) {
                Files.deleteIfExists(p);
            }
            log("Deleted: " + p);
        } catch (IOException e) {
            try {
                log("FAILED DELETE (locked): " + p + " (" + e.getMessage() + ")");
            } catch (IOException fatal) {
                System.err.println("Fatal logging failure, aborting.");
                System.exit(500);
            }
        }
    }

    static void scheduleDelete(Path p, String reason) throws IOException {
        if (Files.isDirectory(p)) {
            scheduledDirs.add(p);
        } else {
            scheduledFiles.add(p);
        }
        log("Scheduled delete: " + p + " (" + reason + ")");
    }

    static boolean directoryContainsZip(Path dir) throws IOException {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                if (Files.isRegularFile(p) && ext(p).equals("zip"))
                    return true;
            }
        }
        return false;
    }

    static Path resolveDup(Path p) {
        if (!Files.exists(p))
            return p;
        int i = 1;
        String n = p.getFileName().toString();
        String b = n.substring(0, n.lastIndexOf('.'));
        String e = n.substring(n.lastIndexOf('.'));
        while (Files.exists(p = p.getParent().resolve(b + "_" + i++ + e)))
            ;
        return p;
    }

    static String ext(Path p) {
        return ext(p.getFileName().toString());
    }

    static String ext(String n) {
        int i = n.lastIndexOf('.');
        return i < 0 ? "" : n.substring(i + 1).toLowerCase();
    }

    static String titleCase(String s) {
        StringBuilder b = new StringBuilder();
        for (String w : s.split(" ")) {
            if (!w.isEmpty())
                b.append(Character.toUpperCase(w.charAt(0)))
                        .append(w.substring(1).toLowerCase()).append(" ");
        }
        return b.toString().trim();
    }

    static void log(String msg) throws IOException {
        log.write(LocalDateTime.now() + " " + msg);
        log.newLine();
        log.flush();
    }

    static Path applyLowercaseRule(Path dir) throws IOException {
        String name = dir.getFileName().toString();
        String lower = name.toLowerCase();

        if (config.forceLowercaseFolders && !name.equals(lower)) {
            Path lowerDir = dir.resolveSibling(lower);
            if (!config.dryRun)
                Files.move(dir, lowerDir);
            log("Renamed system folder: " + dir + " → " + lowerDir);
            return lowerDir;
        }
        return dir;
    }

    static String resolveSystemKey(String folderName) {
        String key = folderName.toLowerCase();
        if (config.aliases.containsKey(key))
            return config.aliases.get(key);
        return key;
    }

    static Path tagAliasedSystemIfNeeded(Path systemDir, String resolvedKey) throws IOException {
        String folderName = systemDir.getFileName().toString().toLowerCase();

        // Already canonical → nothing to do
        if (folderName.equals(resolvedKey))
            return systemDir;

        // Already tagged → avoid infinite renames
        if (folderName.contains(config.aliasSystemTag.toLowerCase()))
            return systemDir;

        // Build tagged name: original_needsRename_canonical
        String taggedName = folderName
                + config.aliasSystemTag
                + "_" + resolvedKey;

        Path taggedDir = systemDir.resolveSibling(taggedName);

        if (!config.dryRun)
            Files.move(systemDir, taggedDir);

        log("Tagged aliased system folder: " + systemDir + " → " + taggedDir);

        return taggedDir;
    }

    static boolean isSingleTxtSystemFolder(Path systemDir) throws IOException {
        int fileCount = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(systemDir)) {
            for (Path p : ds) {
                if (Files.isDirectory(p))
                    return false;

                if (Files.isRegularFile(p)) {
                    fileCount++;
                    if (!ext(p).equals("txt"))
                        return false;
                }
                if (fileCount > 1)
                    return false;
            }
        }
        return fileCount == 1;
    }

    static boolean isEmptySystem(Path systemDir) throws IOException {
        try (Stream<Path> ds = Files.list(systemDir)) {
            return !ds.iterator().hasNext();
        }
    }

    static boolean systemOnlyContainsEmptyFolders(Path systemDir) throws IOException {
        if (!Files.isDirectory(systemDir)) {
            return false;
        }

        try (Stream<Path> stream = Files.walk(systemDir)) {
            return stream
                    .filter(p -> !p.equals(systemDir))
                    .noneMatch(Files::isRegularFile);
        }
    }

    static void deleteDirectoryTree(Path root) throws IOException {
        if (!Files.exists(root))
            return;

        List<Path> toDelete;
        try (Stream<Path> s = Files.walk(root)) {
            toDelete = s.sorted(Comparator.reverseOrder()).toList();
        }

        for (Path p : toDelete) {
            tryDelete(p);
        }
    }

    static void cleanupEmptyDirectories(Path root) throws IOException {
        Files.walk(root)
                .sorted(Comparator.reverseOrder()) // children first
                .filter(Files::isDirectory)
                .forEach(dir -> {
                    try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                        if (!ds.iterator().hasNext()) {
                            Files.deleteIfExists(dir);
                            log("Deleted empty directory (post-cleanup): " + dir);
                        }
                    } catch (IOException e) {
                        try {
                            log("FAILED EMPTY DIR DELETE: " + dir + " (" + e.getMessage() + ")");
                        } catch (IOException ignored) {
                        }
                    }
                });
    }

    // ===== CONFIG =====
    static class Config {
        public boolean dryRun;
        public String rootPath;
        public String logFile;
        public String unknownSystemTag;
        public String aliasSystemTag;
        public boolean forceLowercaseFolders;
        public Map<String, SystemConfig> systems;
        public Map<String, String> aliases;
    }

    static class SystemConfig {
        public Set<String> extensions;
        public String zipPolicy;
        public boolean rommNaming;
    }
}
