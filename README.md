# WarriorView - A High-Performance Damage Display Plugin
*Author: Noogear*

---

## 📦 Plugin Information
- **Dependencies**: [Packetevents](https://modrinth.com/plugin/packetevents) 
- **Version Support**: Paper/Folia and its branches 1.21+

---

## ✨ Core Features
1. **Dynamic Damage/Healing Numbers**  
   - Precisely display damage/healing values with customizable decimal places.
   - Support for different text effects based on damage/healing types.
   - Color and gradient customization using [MiniMessage](https://webui.advntr.dev). 
   - Multiple animation effects (requires configuration of `animation.yml`). 
   - Text replacement feature, allowing character substitution for numbers and text to achieve customized effects.

2. **Multiple Configuration Options**  
   - Text scaling random value support: `0.9,1.0,1.1` or `0.9-1.1`.
   - Each text can be individually configured with text property options.
  ```
   # Displayed text, only supports minimessage color format
   text-format: <red>-%.2f
   # Text replacement feature
   replacement: ''
   # Size of generated text, supports single/multiple values or formats like 0.9-1.1 for random selection
   scale: 0.9, 1.0, 1.1
   # Text shadow
   shadow: true
   # Text opacity, 0 - 100, 100 is opaque
   opacity: 80.0
   # View range
   view-range: 1.0
   # Visibility range for players, set to 0 for visibility to the player only
   view-marge: 16
   # Use ARGB, 0 for transparent
   background-color: 0
   # Whether text can be seen through blocks
   see-through: true
   # Whether to display only player-related text
   only-player: true
   # Position of number display, options include: eye, foot. For attacks between entities, damage represents the precise attack position
   position: eye
   # Animation type
   animation: '1'
   # Movement count
   move-count: 8
   # Movement interval, in ticks
   delay: 2
```

3. **High-Performance Optimization**  
- Packet-based implementation with asynchronous processing, no server load.
- Uses high-version Textdisplay for text rendering, no impact on client performance.

---

## 🛠️ Installation Guide
1. Download `WarriorView.jar`. 
2. Place it in the server's `plugins` folder.
3. Automatic configuration generation upon first startup:
```yaml
   config.yml         # Main configuration
   damage_cause.yml   # Damage cause configuration
   regain_reason.yml  # Healing cause configuration
   animation.yml      # Animation configuration
   replacement.yml    # Text replacement rules
   language.yml       # Language file
