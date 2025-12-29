# ROM Structure Cleaner

## What is this Programm?

This is a Java program designed to be run against a preset ROM structure. It cleans up any files that do not belong in a ROM folder or its subfolders, and it unpacks compressed ROMs (this can be disabled in the configuration).
Generally, it tries to convert a given ROM structure into one that is compatible with the [RomM](https://github.com/rommapp/romm) ROM manager.
Files that do not have any extension will prompt a command line input to either rename, skip or delete.

## Config

In the configuration, systems and file extensions of ROM files are set. An alias can be set so that the programm can work with alternative system names.
The programm will label all 'systems' that aren't in its configuration with a tag, which can also be configured via the configuration, and skip them during 'junk' file removal.
The Zip policy per system can either be set to 'forbid' which means the programm will try to unzipp and remove all zips or allowed in which case the programm will leave all zip files with roms inside alone, no matter what zip files not containing roms will be removed.
