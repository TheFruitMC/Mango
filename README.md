![Mango Banner](https://cdn.modrinth.com/data/cached_images/9fdaf2f8701b6722c72d1d137e701ed1070c6c7f.png)

# Mango

## About

Mango is a client-side rendering optimization mod for Minecraft 26.2, built for the game's Vulkan backend. It reworks the terrain submission path with persistent GPU buffers, indirect rendering, temporal Hi-Z occlusion culling, and GPU-side command compaction. Entity, item, and particle rendering can also use instanced pipelines on supported hardware.

Mango targets smooth frame delivery at higher render distances without sacrificing visual correctness. Its Vulkan-specific paths are enabled automatically when the Vulkan backend is active; on OpenGL, Mango stays out of the rendering pipeline.

## Requirements

* Minecraft 26.2
* Java 26
* A device and driver supporting Vulkan 1.2 or newer
* Fabric Loader 0.19.3 or newer, or NeoForge 26.2

---

## Hardware Compatibility

Mango supports the vast majority of Vulkan 1.2+ GPUs.

If you encounter issues, please make sure your graphics drivers are up to date first.

Outdated drivers may cause rendering errors, crashes, or performance issues.

---

## Contributing

Mango is an open development project, and contributions are welcome.

Your contributions can help improve Mango and make Minecraft rendering more modern.

---

## Reporting Issues

Please report issues through the issue tracker. A precise report helps identify rendering regressions and device-specific driver problems much faster.

When submitting an issue, please include:

* A detailed description of the problem
* Your graphics card and driver version
* Your Minecraft, loader, and Mango versions
* Relevant log files