# Arbeitsregeln & Implementierungs-Notizen

## Umgebung
- Betriebssystem: **Windows**
- Shell: **PowerShell** verwenden
- Keine Linux-Tools voraussetzen (z. B. `tail`, `sh`)

## Build/Validierung
- Standard-Build für Änderungen: `ant clean compile`
- Kompilation erfolgreich: ~31 Sekunden

## Primäre Logs
- Hauptlog zur Laufzeitanalyse: `d:\Repo\yacy_my\DATA\LOG\yacy00.log`

---

## RocksDB-Integration: Architektur-Übersicht

### WICHTIG: Saubere Separation
**RocksDB und Kelondro sind völlig unabhängig.** Es gibt ✗ KEINE Vermischung.

- **Kelondro/Legacy**: ArrayStack + ReferenceContainerArray (unverändert)
- **RocksDB/Neu**: RocksDBReferenceContainerArray + RocksDBBlobStore (separate Implementierung)

### Aktivierung
RocksDB wird aktiviert durch: `defaults/yacy.init`:
```
index.rocksdb.enabled = false       # Set to true to activate RocksDB
index.rocksdb.import.auto = false   # Set to true for auto-import existing blobs
```

Integration in `source/net/yacy/search/index/Segment.java`:
```java
// Try RocksDB first
this.termIndex = RocksDBStorageFactory.createRwiCell(...);
// Fallback zu Legacy wenn RocksDB disabled/unavailable
if (this.termIndex == null) {
    this.termIndex = new IndexCell<WordReference>(...);
}
```

### Wichtige Dateien (RocksDB-Packages)

#### `source/net/yacy/rocksdb/` - Hauptimplementierung
- **RocksDBBlobStore.java**
  - ✓ Off-heap Blob-Storage via RocksDB
  - ✓ `put()` = normale Schreib-Pfad (WAL enabled, sync konfigurierbar)
  - ✓ `putImportFast()` = Import-Pfad (WAL disabled, mit Merging bei mergeRow)
  - ✓ Comparator-Fallback für legacy Datenbanken

- **RocksDBReferenceContainerArray.java**
  - ✓ RocksDB-basierte Container-Verwaltung (analog zu Kelondro's ReferenceContainerArray)
  - ✓ Keine Abhängigkeit von Kelondro-Code

- **RocksDBStorageFactory.java**
  - ✓ Erzeugt RocksDBReferenceContainerArray oder gibt null zurück
  - ✓ Triggert Auto-Import via `tryAutoImportRwiBlobs()`
  - ✓ Flag-basierte Aktivierung

- **RocksDBIndexCell.java**
  - ✓ Top-level Index-Cell für RWI

#### `source/net/yacy/rocksdb/migration/` - Blob-Import
- **HeapBlobImporter.java** (NEW - März 2026)
  - ✓ **Unabhängiger** Import-Orchestrator
  - ✓ Nutzt HeapReader zum Lesen von `.blob`-Dateien
  - ✓ Liest einzelne Blobs iterativ
  - ✓ Keys werden mit YaCy-Encoding preserve
  - ✓ `putImportFast()` macht das Merging (wenn mergeRow gesetzt)
  - ✓ Progress-Reporting: ETA, rec/s, MiB/s, Prozent, `.blob.imported` Umbenennung

### Praktisches Debugging

#### Wenn RocksDB aktiviert wird:
1. `defaults/yacy.init` prüfen: `index.rocksdb.enabled = true`
2. `yacy00.log` nach folgenden Meldungen prüfen:
   - `RocksDBStorageFactory: auto-imported ... entries from heap blobs`
   - `RocksDBBlobStore: opened ... (order=..., normal: WAL=..., import: WAL=...)`
   - `HeapBlobImporter: imported ... entries from ...`
   - `HeapBlobImporter: blob import [1/5] ... | 34.2% | ... rec | ... MiB/s | ETA ...`

#### Wenn nur Legacy aktiviert ist:
1. `index.rocksdb.enabled = false` → RocksDB wird übersprungen
2. Normal-Mode nutzt Kelondro's ArrayStack + ReferenceContainerArray

### Komparator-Mismatch bei bestehenden RocksDB-Datenbanken
- RocksDBBlobStore hat Fallback-Logic (Zeile ~285): Wenn eine bestehende DB einen anderen Comparator hat, wird ohne Comparator erneut versucht
- Log-Meldung: `comparator mismatch...falling back to default comparator (legacy DB)`
- Workaround: Bestehende DB kann laufen, aber sollte später neu importiert werden

---

## Für Folgearbeit
Wenn weitere RocksDB-Änderungen nötig sind:
1. Code-Änderungen in `source/net/yacy/rocksdb/` oder `source/net/yacy/rocksdb/migration/`
2. Keep Kelondro unverändert (keine Vermischung)
3. Dieser Datei aktualisieren mit neuen Pfaden/Flags/Log-Meldungen
