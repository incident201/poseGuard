# Local environment and physical-device scenarios

`workspace/` contains the sources of a tested environment for building, ADB debugging and the Intiface test bench, without Android Studio or an emulator. Tools and reports are kept outside Git.

The environment uses this layout:

```text
PoseGuardDev/
  env.sh
  bin/pg
  tools/
  .tools/          # installed tools, not in Git
  artifacts/      # local results, not in Git
  poseGuard/      # this repository
```

To recreate this layout, place this checkout in a folder named `poseGuard` and copy the contents of `dev/workspace/` into its parent folder. An already configured workspace has this done.

Setup, debugging and commands are described in the [environment README](workspace/README.md); scenarios and results are in [TESTING.md](workspace/TESTING.md). The logs and snapshots mentioned in the results stay as local artifacts and are not part of the repository.
