# VK Diary Export → DOCX

Toolchain to export a VK topic (text, emojis, attached photos) into a structured `.docx` diary document.
## Disclaimer

This project is intended for personal archival and educational purposes only.
Use it only with content you own or have permission to export.

## Workflow

1. **Export VK topic → JSON**  
   Uses Playwright (desktop VK layout) to collect:
    - post text
    - emojis (inline)
    - attached album photos (best available resolution)

2. **Convert JSON → DOCX**  
   Generates a readable document with automatic structure:
    - Year → Month → Date

## Privacy
All personal data and exports are **excluded** from the repository:
- VK sessions
- JSON exports
- images
- generated `.docx` files

## Tech
- Node.js + Playwright
- Java 17 + Apache POI
- Gradle

## Local run

```powershell
cd vk-diary-export
$env:VK_TOPIC_URL="https://vk.com/topic-XXXX_YYYY"
node export_vk_topic_desktop.js
```

After topic.json is created, run the Java converter from project root:

```bash
./gradlew run