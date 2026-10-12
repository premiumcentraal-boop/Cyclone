# Run logger

A Cyclone Ports example plugin. It appends every run event, note and the account's typed fields to `runs.jsonl` and
saves screenshots and files next to it, inside the plugin's own data folder (setting **Folder**). It never receives a
password or a code: the hub doesn't send those to plugins.
