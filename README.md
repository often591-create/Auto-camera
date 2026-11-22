# Auto Camera 📷

依照**手機型號**與**現場環境**，建議你該用幾倍鏡頭拍、站在哪裡。

## 功能
- **自動辨識機型**：App 版直接讀取 Android 系統型號；也能從測試照的 EXIF 辨識（iPhone 可用）。
- **情境建議**：人像、風景、建築、美食、寵物、商品、街拍、夜景、微距。
- **現場測光**：拍一張 1× 測試照，分析亮度、逆光、過曝、背景雜亂度、ISO／快門。
- **站位計算**：依距離與主體大小算出需要的焦距，告訴你往前或往後走多少、相機放多高。
- **取景框**：在照片上畫出建議倍數實際拍到的範圍。

## 下載 APK
到 [Releases](../../releases) 下載最新的 `AutoCamera-xx.apk`，在手機上開啟安裝。
每次推送到 `main`，GitHub Actions 都會自動重新編譯。

## 自己編譯
```bash
npm install
npm run vendor
npx cap add android
npx cap sync android
cd android && ./gradlew assembleDebug
```

## 結構
- `www/index.html`：整個 App（純 HTML/JS）
- `capacitor.config.json`：Capacitor 設定
- `.github/workflows/build-apk.yml`：自動編譯 APK

> 鏡頭規格為概略值，各地版本可能不同。
