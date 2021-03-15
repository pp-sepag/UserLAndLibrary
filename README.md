
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

5) Build the app as normal
It will require you to download a variety of packages via the SDK Manager

## Setting Up your Production Environment

1) Setup assets server
2) Setup filesystem server
3) Install app
4) Customize settings
