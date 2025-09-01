# BMenus

BMenus is a [Geyser](https://geysermc.org/) extension that provides a menu-driven command interface for Bedrock players. It uses the [Cumulus](https://github.com/GeyserMC/Cumulus) forms API to build interactive menus and dynamic command builders from a simple YAML configuration.

## Features

- **Inventory Double-Tap Trigger** – opening the inventory twice in quick succession presents the main menu titled **"BM Commands"**.
- **Menu Types** – supports `simple`, `custom`, and `modal` menus. Buttons may link to other menus or execute command templates directly.
- **Dynamic Arguments** – commands in `menus.yml` can specify interactive arguments that are turned into `CustomForm` components at runtime. Supported argument descriptors:
  - `Input` – `{"Prompt", Input}`
  - `Dropdown` – `{"Prompt", Dropdown, "Option1, Option2"}`
  - `PlayerList` – `{"Prompt", PlayerList}` for listing online players
  - `Toggle` – `{"Prompt", Toggle}`
  - `Slider` – `{"Prompt", Slider, "min, max, step"}`
  - `StepSlider` – `{"Prompt", StepSlider, "Step1, Step2"}`
- **"Common" Menu** – a personalized menu that shows each player’s ten most-used commands. Usage is tracked in memory and periodically flushed to disk, expiring stale entries and limiting per-player history.
- **Configurable Defaults** – new players start with a customizable list of default commands that seed the "Common" menu before any usage is recorded.
- **Hardened Parsing** – unknown argument types fall back to simple input and log warnings instead of crashing.

## Configuration

All menus and usage settings are defined in [`menus.yml`](src/main/resources/menus.yml). A small excerpt is shown below:

```yaml
defaults:
  common:
    - "/spawn"
    - "/wild"
    - "sethome {\"Home Name:\", Input}"
    - "home {\"Home Name:\", Input}"
    - "/lands"
    - "/rankup"
    - "vot {\"Choose an option:\", Dropdown, \"Day, Night, Clear, Sun, Raining, Thunder\"}"
    - "/vot yes"
    - "/vot no"
    - "/rules"

usage:
  flush-interval-seconds: 300    # how often usage is saved
  max-commands: 50               # max stored commands per player
  expiry-seconds: 604800         # prune commands unused for a week

menus:
  main:
    type: simple
    title: "BM Commands"
    buttons:
      - text: "Teleportation"
        menu: "teleportation"
      - text: "Common"
        menu: "common"
```

See the top of `menus.yml` for a fully commented guide detailing every supported component and configuration option.

## Building

This project uses **Maven**. Run the following command to compile the extension:

```sh
mvn -q package
```

The built JAR will be located in `target/`. Copy it into Geyser’s `extensions` folder and restart the proxy to load BMenus. The default `menus.yml` will be generated alongside `extension.yml` on first run.

## Usage

1. Join the server from Bedrock through Geyser.
2. Double-tap the inventory button to open the **BM Commands** main menu.
3. Navigate through configured menus or select a command. If the command template defines arguments, a form will collect the required information before dispatching the final command as the player.
4. Frequently used commands automatically populate the **Common** menu for quick access.

## License

This project is licensed under the [MIT License](LICENSE).

