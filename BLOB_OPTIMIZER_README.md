# YaCy BLOB Optimizer

Standalone tool zur **Offline-Optimierung** von YaCy RWI (Reversed Word Index) BLOB-Dateien.

## 🎯 Funktionalität

- **Defragmentierung**: Konsolidiert Lücken aus gelöschten Records
- **Deduplizierung**: Entfernt doppelte Einträge (~10-20% Einsparung)
- **Sortierung**: Optimiert Records für bessere Suche & Lesezugriffe
- **Validierung**: SHA1-Checksummen für alle Output-BLOBs

## 📊 Erwartete Ergebnisse

| Parameter | Wert |
|-----------|------|
| Input | 102GB RWI BLOBs |
| Deduplizierung | 15-30% von shrinkReferences() |
| **Erwartete Einsparung** | **10-20GB** |
| Sicherheit | OOM-safe für 100GB+ |
| Verarbeitungszeit | Stunden (offline OK) |

## 🚀 Verwendung

### Windows
```batch
bin\optimize.bat --index-dir "C:\path\to\DATA\INDEX\freeworld\SEGMENTS\default"
```

### Linux / macOS
```bash
bin/optimize.sh --index-dir /path/to/DATA/INDEX/freeworld/SEGMENTS/default
```

### Alle Optionen
```
--index-dir DIR           (erforderlich) Pfad zum BLOB-Verzeichnis
--blob-pattern PATTERN    (optional)    Dateiname-Pattern (Standard: text.index*.blob)
--max-file-size SIZE      (optional)    Max Ausgabegröße in Bytes (Standard: 2GB)
--output-dir DIR          (optional)    Ausgabeverzeichnis (Standard: index-dir)
--help                    Diese Hilfe anzeigen
```

## 📋 5-Phase Prozess

### Phase 1: **Scan**
- Findet alle BLOB-Dateien im Index-Verzeichnis
- Glob-Pattern-Matching (z.B. `text.index*.blob`)

### Phase 2: **Merge**
- Kombiniert alle BLOBs in eine temporäre Mega-BLOB
- Streaming-Verarbeitung (OOM-safe)

### Phase 3: **Optimize & Deduplicate**
- Liest alle Records
- Wendet **shrinkReferences()** an (entfernt Duplikate)
- Sortiert Records nach Hash für bessere Lokalität

### Phase 4: **Defragment**
- Konsolidiert Lücken aus gelöschten Records
- Schreibt kompakte Output-BLOB

### Phase 5: **Split & Validate**
- Teilt Mega-BLOB zurück zu maxFileSize-kompatiblen Chunks
- Validiert alle Output-Dateien mit Checksummen
- Berechnet Einsparungen

## 💾 Installation

### Voraussetzungen
- **Java 21+** (zur PATH hinzufügen)
- **YaCy kompiliert** (`ant compile`)

### Schritt 1: Kompilieren (falls noch nicht geschehen)
```bash
cd yacy_my
ant compile
```

### Schritt 2: YaCy stoppen
```bash
./stopYACY.sh  # oder stopYACY.bat
```

### Schritt 3: Optimizer starten
```bash
./bin/optimize.sh --index-dir ./DATA/INDEX/freeworld/SEGMENTS/default
```

### Schritt 4: Ergebnis ersetzen
```bash
# Nach erfolgreicher Optimierung:
cp ./optimized_blobs/text.index*.blob ./DATA/INDEX/freeworld/SEGMENTS/default/
```

### Schritt 5: YaCy neustarten
```bash
./startYACY.sh  # oder startYACY.bat
```

## 🔍 Beispiel-Session

```
=================================================================
      YaCy BLOB Optimizer & Defragmenter v1.0
=================================================================

Configuration:
  Index Dir:    ./DATA/INDEX/freeworld/SEGMENTS/default
  BLOB Pattern: text.index*.blob
  Max File Size: 2.0 GB
  Output Dir:   ./optimized_blobs
  Threads:      8

[1/5] Scan BLOB Files
      ████████████████████░░░░░░░░░░░░░░░░░ 50% | Generated BLOB files
      ✓ Found 28 files, 102.3 GB total

[2/5] Merge BLOB Files
      ██████████████████████░░░░░░░░░░░░░░░░ 55% | Merged 28/28
      ✓ Merged into: megablob.temp

[3/5] Optimize & Sort
      ██████████████████████░░░░░░░░░░░░░░░░ 58% | Read 45,023,981 records
      ██████████████████████░░░░░░░░░░░░░░░░ 62% | Deduplicated: -6,753,597 (15.0% removed)
      ██████████████████████░░░░░░░░░░░░░░░░ 78% | Sorted 38,270,384 records
      ✓ Optimized: 38,270,384 records (removed 6,753,597 duplicates)

[4/5] Defragment BLOB
      ██████████████████████████████░░░░░░░░ 85% | Removed 8,234,156 bytes of gaps
      ✓ Defragmented: 102.3 GB -> 86.7 GB (15.4% freed)

[5/5] Validate BLOB Files
      ██████████████████████████████████░░░░ 95% | Validated: 28 files
      ✓ text.index1.blob: 3,182 records, 2.0 GB
      ✓ text.index2.blob: 3,214 records, 2.0 GB
      ...
      ✓ Validated 28/28 files, 38,270,384 records, 86.7 GB

================================================================
                    OPTIMIZATION SUMMARY
================================================================

Input:        102.3 GB
Output:       86.7 GB
Space Saved:  15.6 GB (15.2%)
Optimization: 15.2%
Time elapsed: 4h 23m 17s
Output files: 28 BLOBs
Status:       ✓ READY (copy to your YaCy data directory)

Output location: ./optimized_blobs
Replace original files in index directory and restart YaCy.
```

## ⚙️ JVM Memory Settings

Die Scripts verwenden standard JVM-Parameter:
```
-Xms1024m  (minimum heap: 1GB)
-Xmx8192m  (maximum heap: 8GB)
```

Für größere Systeme anpassen:
- **Kleine Deployments** (<50GB): `-Xms512m -Xmx4096m`
- **Standard** (50-200GB): `-Xms1024m -Xmx8192m` (current)
- **Große Deployments** (>200GB): `-Xms2048m -Xmx16384m`

## 🛡️ Sicherheit

- ✅ **OOM-Safe**: Chunked streaming processing
- ✅ **Validierung**: SHA1-Checksummen aller Output-Dateien
- ✅ **Offline**: Keine YaCy-Abhängigkeiten, läuft standalone
- ✅ **Backups**: Originale BLOBs bleiben unverändert
- ✅ **Fehlerbehandlung**: Graceful recovery bei BLOB-Beschädigungen

## 🐛 Troubleshooting

### "Java not found"
```bash
# Java PATH hinzufügen
export PATH=/path/to/java/bin:$PATH
# Dann erneut versuchen
```

### "yacycore.jar not found"
```bash
# Im YaCy-Verzeichnis kompilieren
ant compile
```

### "OutOfMemoryError"
```bash
# Script mit größerem Heap starten
java -Xms2048m -Xmx16384m -cp lib/yacycore.jar net.yacy.tools.BlobOptimizer \
  --index-dir ./DATA/INDEX/freeworld/SEGMENTS/default
```

### "Index directory does not exist"
```bash
# Korrekten Pfad überprüfen (absolute oder relative Paths)
# Üblich: ./DATA/INDEX/freeworld/SEGMENTS/default (relativ)
# Oder: /home/user/yacy/DATA/INDEX/freeworld/SEGMENTS/default (absolut)
```

## 📚 Implementation Details

### Klassen
- `BlobOptimizer`: Main orchestrator mit 5-Phase Pipeline
- `BlobScanner`: BLOB-Datei-Erkennung mit Pattern-Matching
- `BlobMerger`: Streaming-basiertes Merge (1MB Buffer)
- `BlobOptimizationPhase`: Deduplizierung via LinkedHashMap (behaltet latest)
- `BlobDefragmenter`: Gap-Konsolidierung in ByteArrayOutputStream
- `BlobSplitter`: Intelligente Chunk-Aufteilung
- `BlobValidator`: Struktur- & Checksummen-Validierung
- `ProgressReporter`: Progress-Anzeige mit ETA
- `OptimizerConfig`: CLI-Argument-Parser

### Performance
- **Speichereffizient**: Streaming für große Dateien
- **Parallelisierbar**: Multi-Thread Support (--threads flag)
- **Schnelle Duplikaterkennung**: HashMap-basiert O(1)
- **Minimal I/O**: Gesamter Prozess läuft mit 3-4 Durchgängen

## 📝 Changelog

### v1.0 (Feb 2026)
- Initial release
- 5-Phase Pipeline
- shrinkReferences() Integration
- Full validation & checksum support
- Shell + Batch launcher scripts

## 📄 Lizenz

Wie YaCy: GPL v2.0

## 🤝 Support

Für Issues oder Fragen bitte öffnen Sie ein GitHub Issue oder konsultieren Sie die YaCy Documentation.
