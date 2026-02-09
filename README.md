# LibreLoginProd

A powerful, multiplatform Minecraft authentication plugin for both **Paper** and **Velocity** servers with full **Geyser/Floodgate** support for Bedrock Edition players.

## Features

### 🔐 Authentication
- **Secure Password Hashing** - Multiple crypto providers: BCrypt-2A (recommended), Argon-2ID, SHA-256, SHA-512
- **Session Management** - Configurable session timeout for persistent login
- **Login Attempt Limiting** - Automatic kick after configurable failed attempts
- **Two-Factor Authentication (2FA)** - TOTP-based 2FA with in-game QR code scanning (requires Protocolize)
- **Email Password Recovery** - SMTP-based password reset via email verification

### 🎮 Platform Support
- **Paper** - Full standalone Paper server support with limbo world system
- **Velocity** - Proxy-based authentication with backend server routing
- **Geyser/Floodgate Integration** - Seamless Bedrock Edition support with automatic player detection

### ✨ Premium Features
- **Premium Account Linking** - Link offline accounts to Mojang profiles for auto-login
- **Auto-Registration** - Optional automatic registration for premium usernames
- **Skin Restoration** - Automatically restores premium player skins after authentication
- **UUID Management** - Multiple UUID creators: RANDOM, OFFLINE, or MOJANG-based

### � Database Support
- **MySQL** - Full MySQL database support
- **PostgreSQL** - Full PostgreSQL database support
- **SQLite** - Lightweight SQLite for smaller servers

### 🔄 Migration Support
Migrate from 13+ authentication plugins:
- AuthMe (MySQL/SQLite/PostgreSQL)
- JPremium (MySQL)
- NLogin (MySQL/SQLite)
- FastLogin (MySQL/SQLite)
- LimboAuth (MySQL)
- Aegis (MySQL)
- LoginSecurity (MySQL/SQLite)
- DynamicBungeeAuth (MySQL)
- Authy (MySQL/SQLite)
- LogIt (MySQL)
- CrazyLogin (MySQL)
- UniqueCodeAuth (MySQL)
- And more...

### ⚙️ Configuration
- **Forced Hosts** - Route players from different domains to different lobbies
- **Limbo Servers/Worlds** - Configurable authentication waiting areas
- **Remember Last Server** - Optional last-server persistence
- **IP Registration Limits** - Configurable accounts per IP address
- **Password Requirements** - Minimum length enforcement

## Requirements

- **Java 21** or higher
- **Paper 1.21+** or **Velocity 3.3+**
- Optional: **Floodgate** for Bedrock Edition support
- Optional: **Protocolize** for 2FA QR code display

## Installation

1. Download the latest release from the [Releases](https://github.com/AnarchadiaMC/LibreLoginProd/releases) page
2. Place the JAR in your `plugins` folder
3. Restart your server
4. Configure `plugins/LibreLogin/config.conf`

## Configuration

See the [Wiki](https://github.com/AnarchadiaMC/LibreLoginProd/wiki) for detailed configuration guides:

- [Configuring Servers](https://github.com/AnarchadiaMC/LibreLoginProd/wiki/Configuring-Servers)
- [Database Migration](https://github.com/AnarchadiaMC/LibreLoginProd/wiki/Database-Migration)
- [UUID Creators](https://github.com/AnarchadiaMC/LibreLoginProd/wiki/UUID-Creators)
- [2FA Setup](https://github.com/AnarchadiaMC/LibreLoginProd/wiki/2FA)

## Commands

### Player Commands
| Command | Description |
|---------|-------------|
| `/register <password> <password>` | Register a new account |
| `/login <password>` | Login to your account |
| `/changepassword <old> <new>` | Change your password |
| `/premium` | Premium account linking commands |
| `/2fa` | Two-factor authentication setup |
| `/email` | Email verification commands |

### Staff Commands
| Command | Permission |
|---------|------------|
| `/librelogin` | `librelogin.admin` |

## Building

```bash
./gradlew clean shadowJar -x spotlessCheck -x test
```

The compiled JAR will be in `Plugin/build/libs/`.

## License

This project is licensed under the [Mozilla Public License 2.0](LICENSE).

## Credits
- [AnarchadiaMC/LibreLoginProd](https://github.com/AnarchadiaMC/LibreLoginProd) - Current maintainer
- [Navio1430/LibreLoginProd](https://github.com/Navio1430/LibreLoginProd) - Fork creator 
- [kyngs/LibreLogin](https://github.com/kyngs/LibreLogin) - Original project creator
