### matchbook
###### A lightweight modding tool for The Witcher 3
=========

### About

bundle-explorer is a very basic Java program that allows you to modify the contents of the '.bundle' files that are used by The Witcher 3 to packing all in-game content.  

This program may also work for other games that make use of the same packaging format, however its intended purpose is to assist in modding The Witcher 3, and that is the only game I have tested it with.  


### Getting Started

If you just want a packaging tool to help you create your Witcher 3 mod, I recommend downloading the prebuilt 'bundle-explorer.jar' file.  This is by far the simplest option if you don't want/need to customize the bundle-explorer source code.

Otherwise, just check out the sources and open the project in Eclipse (or your preferred IDE).  There's only one class file, so it should be pretty easy.


### Usage

The bundle-explorer program expects three command-line arguments that tell it 1) what bundle to modify, 2) where to write the modified bundle to, and 3) where to look for modded files.

Specifically, you can run the program (after downloading or building 'bundle-explorer.jar'), like:

'_java -Xms1g -jar bundle-explorer.jar [INPUT_FILE] [OUTPUT_FILE] [MOD_DIRECTORY]_'

...for example:

'_java -Xms1g -jar bundle-explorer.jar patch.bundle patch.bundle.mod mod_files/install_'

...assuming that you want to modify 'patch.bundle', write your output to 'patch.bundle.mod', and have placed your modded files under 'mod_files/install' (all relative to the location of 'bundle-explorer.jar').

The '-Xms1g' argument isn't strictly required, but is recommended if you're attempting to mod a large bundle file.  The program will load the bundle contents into memory, so obviously you need to give Java an amount of RAM that is at least slightly larger than the target bundle size.

Note that bundle-explorer also supports a fourth argument which, if set, will cause it to automatically replace the specified input file with the generated output file upon completion of the run (a backup of the input file is automatically created, first).  You can enable this option by passing '-r' as the final argument.  For example:

'_java -Xms1g -jar bundle-explorer.jar patch.bundle patch.bundle.mod mod_files/install -r_'

...will function identically to the command in the previous example, and will additionally move 'patch.bundle.mod' to 'patch.bundle', effectively "installing" the modified bundle. 


### Example

Sometimes it's more effective to show than to tell.  Here's a functional mod that was created using/is distributed and installs with bundle-explorer:

http://www.nexusmods.com/witcher3/mods/264

The mod structure and installation batch scripts should provide a solid reference for how to use bundle-explorer effectively.


### FAQ

**Why create bundle-explorer?**
Because I was trying to create my own mod for The Witcher 3, and found that the de-facto modding tool 1) had known issues that prevented it from reimporting the particular file I was trying to mod, and 2) was artificially constrained in that it did not allow the size of any modded file to exceed the size of the original source file.  

bundle-explorer's primary purpose was to solve the first point.  However it also solves the second.

**_Why should I use bundle-explorer?_**<br />
I assume because you're a Witcher 3 modder whose efforts are being thwarted by the bugs/limitations imposed by other modding tools.  That's pretty much the only conceivable reason for using this.

**_Why shouldn't I use bundle-explorer?_**<br />
Don't use bundle-explorer if you're not a Witcher 3 modder, or if the other modding tools available are working for you, or if you're trying to mod a DLC package (I tried this and couldn't get it to work, I think due to issues with the DLC's 'metadata.store' file, which unlike the main metadata files the game _will not_ regenerate).

**_Are there any limitations to what bundle-exploere can do?_**<br />
The biggest limitation is that bundle-explorer does not compress any modified files when it adds them back into the bundle.  All modded content is written into the bundle with compression disabled.  For most mods this is not a problem.  However, if your mod involves tons of graphics, audio, or modeling work spread across multiple gigabytes of files, this is probbaly not the tool for you.


### License

I'm of the opinion that when someone takes something valuable, like source code, and knowingly and willingly puts it somewhere where literally anyone in the world can view it and grab a copy for themselves, as I have done, they are giving their implicit consent for those people to do so and to use the code however they see fit.  I think the concept of "copyleft" is, quite frankly, borderline insane.  

Information wants to be free without reservation, and good things happen when we allow it to be.  But not everyone agrees with that philosophy, and larger organizations like seeing an "official" license, so I digress.

For the sake of simplicity, you may consider all bundle-explorer code to be licensed under the terms of the MIT license.  Or if you prefer, the Apache license.  Or CC BY.  Or any other permissive open-source license (the operative word there being "permissive").  Take your pick.  Basically use this code if you like, otherwise don't.  Though if you use bundle-explorer to build something cool that changes the world, please remember to give credit where credit is due.  And also please tell me about it, so that I can see too.  



