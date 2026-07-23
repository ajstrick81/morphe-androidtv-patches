# Project References & Architectural Sources

A running list of key resources, open-source projects, and technical analyses that informed the design, API understanding, and logic of this project.

We are grateful to the many communities and individual contributors — including **RookieEnough**, **hoodles**, **Paresh**, and the projects listed below — whose prior work made this project possible. This document is intended to be a living list: as we continue to learn from and build on new resources, we add them here to keep credit where credit is due.

---

### 1. Network Blocking & Host Filters

* **AmazOff**
  * **Repository:** [azoffshowy / AmazOff](https://github.com/azoffshowy/AmazOff)
  * **Summary:** Open-source project targeting Amazon telemetry, tracking, and advertisement domain mitigation.

---

### 2. Automation & Client-Side Scripts

* **Prime Video Enhancer**
  * **Repository:** [bernardopg / primevideo-enhancer](https://github.com/bernardopg/primevideo-enhancer)
  * **Summary:** Userscript designed to improve the Prime Video web player by automating ad skipping, hiding X-ray overlays, and managing cursor visibility.

* **Auto-Skip for Amazon Video Ads / Promos**
  * **Source:** [GitHub Gist: NullDev / 1-README.md](https://gist.github.com/NullDev/485d4b51fcab5751ab0b1f5ba5fd6745)
  * **Summary:** Automation script for automatically detecting and skipping promotional breaks and interstitials on Amazon Prime Video.

* **Amazon Prime Ad Muter Userscript**
  * **Source:** [GitHub Gist: Nate-Wilkins / amazon_prime_ad_muter.userscript](https://gist.github.com/Nate-Wilkins/a3725d5d40dd10e22020985113bdbfb3)
  * **Summary:** Open-source userscript implementation for detecting and muting playback/audio during advertisement segments on Amazon Prime Video.

* **Netflix-Prime-Auto-Skip**
  * **Repository:** [Dreamlinerm/Netflix-Prime-Auto-Skip](https://github.com/Dreamlinerm/Netflix-Prime-Auto-Skip)
  * **Summary:** Userscript automation for handling web playback state, skipping intro/recap sequences, and suppressing ads.

---

### 3. Patches & Extensions

* **Zee5 Patch (Morphe Android TV Patches Fork)**
  * **Repository Path:** [WZSE / morphe-androidtv-patches (`zee5` patch)](https://github.com/WZSE/morphe-androidtv-patches/tree/main/patches/src/main/kotlin/ajstrick81/morphe/patches/zee5)
  * **Summary:** Forked repository containing Kotlin patch implementations for Zee5 client-side modifications and playback suppression mechanisms on Android TV.

* **Chiggi Morphe Patches**
  * **Repository:** [durgesh0505 / chiggi_morphe_patches](https://github.com/durgesh0505/chiggi_morphe_patches)
  * **Summary:** Community patch repository for Morphe, containing custom modifications, patch definitions, and build releases for client-side app extensions.

---

### 4. Side Projects & Other Platforms

* **Twitch Ad-Free WebOS**
  * **Repository:** [tigercraft4 / twitch-adfree-webos](https://github.com/tigercraft4/twitch-adfree-webos)
  * **Summary:** WebOS application implementation for Twitch that handles ad suppression, automated channel point claiming, subscriber VOD unlocking, and UI customization.

* **Twitch Ad-Free TizenBrew**
  * **Repository:** [nipkownix / twitch-adfree-tizenbrew](https://github.com/nipkownix/twitch-adfree-tizenbrew)
  * **Summary:** TizenBrew module port for Samsung Tizen OS targeting Twitch ad suppression, automated channel point claiming, subscriber VOD access, and custom chat overlay styling.

* **PrimeVideoTB**
  * **Repository:** [HighwindBR / PrimeVideoTB](https://github.com/HighwindBR/PrimeVideoTB)
  * **Summary:** TizenBrew module for Amazon Prime Video on Samsung Tizen OS, implementing enhanced playback features and ad-blocking capabilities.

---

### 5. Reverse Engineering & Protocol Analysis

* **Netflix Web Architecture Analysis (Network Traffic Reverse-Engineering)**
  * **Source:** [GitHub Gist: sshh12 / Netflix System Design Reverse-Engineered](https://gist.github.com/sshh12/dda3a89514f850c459380b18b1f7eb7b#file-netflix-system-design-reverse-engineered-md)
  * **Summary:** Empirical analysis of Netflix's web architecture and network protocol layer derived strictly from 177 captured live network requests.
  * **Key Architectural Discoveries:**
    * **Internal Service Ecosystem:** Identified 18 named microservices/internal systems (including *Akira*, *Cadmium*, *Shakti*, *Pinot*, *MSL*, *FTL*, and *Ichnaea*).
    * **API Migration:** Mapped the structural transition from Falcor JSON Graph endpoints to GraphQL schemas across client operations.
    * **Streaming & Security Pipelines:** Documented end-to-end video streaming delivery, Media Source Extensions (MSE) setup, and DRM license acquisition flows (MSL protocol).
    * **Data Modeling:** Cataloged client-side content data models, search capability negotiations, and real-time playback monitoring/telemetry payloads.
