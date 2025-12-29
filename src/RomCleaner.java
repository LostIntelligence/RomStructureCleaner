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
        final Path systemDir = resolveSystemDirectory(dir);

        String folderName = systemDir.getFileName().toString();
        String systemKey = normalizeSystemName(folderName);
        SystemConfig sys = config.systems.get(systemKey);

        if (sys == null) {
            tagUnknownSystem(systemDir);
            return;
        }

        // Rename folder if normalized name differs
        if (!folderName.equals(systemKey)) {
            Path renamed = systemDir.resolveSibling(systemKey);
            if (!config.dryRun) Files.move(systemDir, renamed);
            log("Renamed system folder: " + systemDir + " → " + renamed);
        }

        final SystemConfig finalSys = sys;
        final Path finalSystemDir = systemDir.resolveSibling(systemKey);

        Files.walkFileTree(finalSystemDir, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (file.getParent().equals(finalSystemDir))
                    return FileVisitResult.CONTINUE;

                String ext = ext(file);

                if (ext.equals("zip")) {
                    handleZip(file, finalSystemDir, finalSys);
                } else if (ext.isEmpty()) {
                    handleNoExtension(file);
                } else if (!finalSys.extensions.contains(ext)) {
                    delete(file, "Invalid extension");
                } else {
                    moveRom(file, finalSystemDir, finalSys);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                if (!d.equals(finalSystemDir) && isEmpty(d))
                    delete(d, "Empty directory");
                return FileVisitResult.CONTINUE;
            }
        });
    }

    // ===== ZIP HANDLING =====
    static void handleZip(Path zip, Path target, SystemConfig sys) throws IOException {
        ZipFile zf;
        try {
            zf = new ZipFile(zip.toFile());
        } catch (ZipException e) {
            log("Corrupt or invalid ZIP: " + zip + " (" + e.getMessage() + ")");
            if ("forbid".equals(sys.zipPolicy) || "extract".equals(sys.zipPolicy)) {
                delete(zip, "Invalid ZIP (policy forbids ZIP)");
            }
            return;
        }

        List<ZipEntry> roms = new ArrayList<>();
        List<ZipEntry> junk = new ArrayList<>();

        for (ZipEntry e : Collections.list(zf.entries())) {
            if (e.isDirectory()) continue;
            if (sys.extensions.contains(ext(e.getName()))) roms.add(e);
            else junk.add(e);
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
                Files.copy(is, out, StandardCopyOption.REPLACE_EXISTING);
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
        if (dot < 0) dot = name.length();
        String base = name.substring(0, dot)
                .replace('_', ' ')
                .replaceAll("\\[.*?]", "")
                .replaceAll("\\s+", " ")
                .trim();
        String extPart = dot < name.length() ? name.substring(dot).toLowerCase() : "";
        return titleCase(base) + extPart;
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

    // ===== NEW: normalize system name using aliases =====
    static String normalizeSystemName(String raw) {
        String lower = raw.toLowerCase();
        return config.aliases != null ? config.aliases.getOrDefault(lower, lower) : lower;
    }

    // ===== CONFIG =====
    static class Config {
        public boolean dryRun;
        public String rootPath;
        public String logFile;
        public String unknownSystemTag;
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
