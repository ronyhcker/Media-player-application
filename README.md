# 🎵 NOVA — Smart Media Player

## 🌐 Live Demo
👉 [Click here to open the app](https://media-player-application.onrender.com)

A dark/neon themed smart media player...

## 📁 Project Structure

```
nova-media-player/
├── smart-media-player.html   ← Frontend UI
├── NovaMediaServer.java      ← Java Backend
├── media_files/              ← Auto-created folder for uploads
└── README.md
```

---

## 🚀 Quick Start

### 1. Run the Java Backend

```bash
# Compile
javac NovaMediaServer.java

# Run
java NovaMediaServer
```

Server starts at: (https://media-player-application.onrender.com)

> Requires Java 11+ (uses `com.sun.net.httpserver`)

---

### 2. Open the Frontend

**Option A — With Backend (Recommended)**
- Place `smart-media-player.html` in the same folder as `NovaMediaServer.java`
- Visit `http://localhost:8080` in your browser

**Option B — Standalone**
- Just open `smart-media-player.html` directly in your browser
- Use the built-in file upload (works without backend)

---

## 🎛️ Features

| Feature | Description |
|--------|-------------|
| 🎵 MP3 Support | Play all major audio formats (MP3, WAV, OGG, AAC) |
| 🎬 MP4 Support | Play video files with inline video view |
| 📊 Visualizer | Real-time audio frequency visualizer with neon glow |
| 📋 Playlist | Add, remove, and navigate tracks |
| ⏯️ Controls | Play, Pause, Previous, Next, Stop |
| 🔀 Shuffle | Random track playback |
| 🔁 Repeat | Loop current track |
| 🔊 Volume | Adjustable volume with slider |
| ⏩ Seek | Click progress bar to seek |

---

## 🌐 Java Backend API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/` | Serves the frontend HTML |
| `POST` | `/api/upload` | Upload media files (multipart) |
| `GET` | `/api/playlist` | Get current playlist as JSON |
| `DELETE` | `/api/delete?file=name` | Remove a file from server |
| `GET` | `/media/{filename}` | Stream media (supports range requests) |

---

## 🎨 Tech Stack

- **Frontend**: HTML5, CSS3, JavaScript (Web Audio API, Canvas API)
- **Backend**: Java (built-in `com.sun.net.httpserver`)
- **Fonts**: Orbitron + Rajdhani (Google Fonts)
- **No external dependencies** for Java backend!

---

## 💡 Tips

- Drag & drop multiple files onto the upload zone
- Video files show inline in the artwork area
- Audio files show an animated neon artwork
- The visualizer responds to audio frequency in real time
- Works fully offline with Option B (standalone HTML)
