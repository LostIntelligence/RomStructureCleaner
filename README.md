# ROM Structure Cleaner

## What is this Program?

This is a Java program designed to be run against a preset ROM directory structure. It cleans up files that do not belong in a ROM system folder or its subfolders and optionally unpacks compressed ROMs based on per-system configuration.

The program aims to normalize a ROM collection into a structure compatible with the  
[RomM](https://github.com/rommapp/romm) ROM manager.

Files without a file extension will prompt a command-line decision to **rename**, **skip**, or **delete**.

---

## Recommendation

Because this program **moves and deletes files**, it should always be run against a **test copy** of your ROM collection.

For additional safety:

- Run with `"dryRun": true` in the configuration
- Review the generated log file carefully
- Only disable dry-run once you are confident no unintended mass deletions will occur

---

## Configuration

The configuration defines:

- Supported systems (RomM platform names)
- Valid ROM file extensions per system
- ZIP handling rules per system
- System name aliases for alternative folder naming schemes

### System Detection & Aliases

Each top-level folder under `rootPath` is treated as a system folder.

If a folder name does not directly match a configured system, aliases are applied (case-insensitive, normalized to lowercase).

If a system is still unrecognized:

- The folder is renamed using a configurable tag (e.g. `_missingSystemInfo`)
- The folder is **excluded** from cleanup and deletion logic

---

### ZIP Policy

Each system defines a `zipPolicy`:

- **forbid**  
  ZIP files are not allowed.  
  The program will attempt to extract ROMs and remove the ZIP afterward.

- **allow**  
  ZIP files containing valid ROMs are left untouched.  
  ZIP files without valid ROMs are deleted.

- **require**  
  ROMs must remain zipped (e.g. MAME, Neo Geo, FBNeo).  
  Non-ZIP files are removed.

> By default, most cartridge and disc-based systems should use `forbid`.  
> Arcade systems typically use `require`.

---

### Multi-File Game Folders (RomM-Compatible)

If a **game is already organized into a folder** inside a system directory and:

- The folder contains **at least one valid ROM file** at its root
- AND contains one or more of the following subfolders:
  - dlc
  - hack
  - manual
  - mod
  - patch
  - update
  - demo
  - translation
  - prototype

Then the entire game folder is **left untouched**.

This preserves RomM-compatible layouts for games with updates, DLC, mods, or multiple components.

---

### Logging

All destructive actions are logged, including:

- Deleted files
- Extracted ZIPs
- Skipped multi-file game folders
- Tagged unknown systems

The log file location is configurable.

---

## Summary

- Designed specifically for **RomM compatibility**
- Safe by default when using `dryRun`
- ZIP behavior is **explicit and per-system**
- Existing structured game folders are preserved
- Unknown systems are tagged, not destroyed
