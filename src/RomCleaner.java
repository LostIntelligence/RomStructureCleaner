import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.util.*;
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

    public static void main(String[] args) throws Exception {
        config = new ObjectMapper().readValue(new File("config.json"), Config.class);
        log = Files.newBufferedWriter(Paths.get(config.logFile),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        Path root = Paths.get(config.rootPath);

        for (Path dir : Files.list(root).filter(Files::isDirectory).toList()) {
            processSystemFolder(dir);
        }

        log.close();
    }

    static void processSystemFolder(Path dir) throws IOException {

    // Step 1: normalize folder name (case handling)
    Path resolvedDir = resolveSystemDirectory(dir);

    String folderName = resolvedDir.getFileName().toString().toLowerCase();
    String resolvedKey = resolveSystemKey(folderName);

    SystemConfig sys = config.systems.get(resolvedKey);

    if (sys == null) {
        tagUnknownSystem(resolvedDir);
        return;
    }

    resolvedDir = tagAliasedSystemIfNeeded(resolvedDir, resolvedKey);
    final Path systemDir = resolvedDir;
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
            if (file.getParent().equals(systemDir))
                return FileVisitResult.CONTINUE;

            String ext = ext(file);

            if (ext.equals("zip")) {
                handleZip(file, systemDir, sys);
            } else if (ext.isEmpty()) {
                handleNoExtension(file);
            } else if (!sys.extensions.contains(ext)) {
                delete(file, "Invalid extension");
            } else {
                moveRom(file, systemDir, sys);
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
            if (!d.equals(systemDir) && isEmpty(d))
                delete(d, "Empty directory");
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
        ZipFile zf;
        try {
            zf = new ZipFile(zip.toFile());
        } catch (ZipException e) {
            log("Corrupt or invalid ZIP skipped: " + zip + " (" + e.getMessage() + ")");
            if (sys.zipPolicy.equals("forbid") || sys.zipPolicy.equals("extract")) {
                delete(zip, "Invalid ZIP (policy forbids ZIP)");
            }
            return;
        }

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
            delete(zip, "ZIP contained no valid ROMs");
            return;
        }

        switch (sys.zipPolicy) {
            case "forbid" -> {
                extract(zf, roms, target, sys);
                delete(zip, "ZIP forbidden");
            }
            case "extract" -> {
                extract(zf, roms, target, sys);
                delete(zip, "ZIP extracted");
            }
            case "allow" -> {
                if (!junk.isEmpty() || roms.size() > 1) {
                    promptZipDecision(zip, zf, roms, target, sys);
                }
            }
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
            delete(zip, "User deleted ZIP");
    }

    static void extract(ZipFile zf, List<ZipEntry> roms,
            Path target, SystemConfig sys) throws IOException {
        for (ZipEntry e : roms) {
            Path out = resolveDup(target.resolve(cleanName(e.getName(), sys)));
            try (InputStream is = zf.getInputStream(e)) {
                Files.copy(is, out);
            }
            log("Extracted: " + out);
        }
    }

    // ===== ROM HANDLING =====
    static void moveRom(Path file, Path target, SystemConfig sys) throws IOException {
        String name = sys.rommNaming ? cleanName(file.getFileName().toString(), sys)
                : file.getFileName().toString();
        Path out = resolveDup(target.resolve(name));
        if (!config.dryRun)
            Files.move(file, out);
        log("Moved: " + file + " → " + out);
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
            delete(f, "User deleted");
        if (c.equals("r")) {
            System.out.print("Extension: ");
            Files.move(f, f.resolveSibling(f.getFileName() + "." + in.nextLine()));
        }
    }

    static void tagUnknownSystem(Path dir) throws IOException {
        if (dir.getFileName().toString().endsWith(config.unknownSystemTag))
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

    static void delete(Path p, String reason) throws IOException {
        if (!config.dryRun)
            Files.deleteIfExists(p);
        log("Deleted: " + p + " (" + reason + ")");
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

    static Path resolveSystemDirectory(Path dir) throws IOException {
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
        if (folderName.contains(config.aliasSystemTag))
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
