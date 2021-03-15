
# Welcome to RELAG-System®

A customized version of UserLAnd made specifically for SEP LOGISTIK AG

## Building

Note: I do all this on the latest 

1) Clone the repo including sub repos.

`git clone --recurse-submodules https://github.com/CypherpunkArmory/UserLAnd-Private.git`

3) Download extract the binary assets needed by bVNC
```
cd UserLAnd-Private/remote-desktop-clients-private/`
wget https://github.com/CypherpunkArmory/remote-desktop-clients-private/releases/download/dependencies2/remote-desktop-clients-libs-1.tar.gz
./download-prebuilt-dependencies.sh
```

4) Open Android Studio 

I am using Android Studio 4.2 Beta 3 specifically
You will open project and point it at the UserLAnd-Private directory

5) Build the app as normal

Note: It will require you to download a variety of packages via the SDK Manager

## Setting Up your Production Environment

1) Setup assets server

You will need an asset server running on the network accessible to the device of interest.  This server will need to have a structure similar to what is seen here https://github.com/CypherpunkArmory/UserLAnd-Assets-Support/tree/staging/apps at the provided URL.  Specifically it needs an assets.txt file and a directory per app matching the app name.  Inside each of those directories it needs a .sh a .txt and a .png all matching the app name.

2) Setup filesystem server

If you are going to be downloading filesystems, as opposed to restoring them manually from a previous filesystem export, you will need a filesystem server accessible to the device.  This must have a rootfs-arch.tar.gz for each supported architecture at the path provided.  These should be named similar to what you see here: https://github.com/CypherpunkArmory/UserLAnd-Assets-Ubuntu/releases/latest

3) Install app

4) Restore filesystem from back up (optional, needed if not doing step 2)

5) Customize settings

You can specify the location of the servers on the network and change many other settings from the app settings.
