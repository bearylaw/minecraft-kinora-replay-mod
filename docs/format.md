# The `.kinora` file format, version 1.0

A `.kinora` file is a header followed by an append-only sequence of chunks, ending, once the recording
is finished, with an index chunk and a fixed-size trailer. All integers are little-endian. `varint` and
`varlong` are unsigned LEB128; `zigzag` is zig-zag encoded LEB128; `string` is a varint byte length
followed by UTF-8.

The reference implementation is `dev.kinora.core.format` in `kinora-core`.

## Header (48 bytes)

| Offset | Size | Field |
| --- | --- | --- |
| 0 | 8 | magic `89 4B 4E 52 0D 0A 1A 0A` |
| 8 | 2 | format major (1) |
| 10 | 2 | format minor (0) |
| 12 | 4 | flags, reserved, 0 |
| 16 | 16 | file id: UUID most significant half, then least significant half |
| 32 | 8 | creation time, ms since the Unix epoch |
| 40 | 4 | reserved, 0 |
| 44 | 4 | CRC32C of bytes 0..43 |

## Chunk (40-byte header + payload)

| Offset | Size | Field |
| --- | --- | --- |
| 0 | 4 | sync `KCHK` |
| 4 | 4 | type FourCC (first character in the lowest byte) |
| 8 | 2 | flags: bit 0 zstd, bit 1 critical |
| 10 | 2 | reserved, 0 |
| 12 | 8 | sequence number, 0-based, +1 per chunk |
| 20 | 4 | stored payload length |
| 24 | 4 | raw payload length |
| 28 | 4 | CRC32C of the stored payload |
| 32 | 4 | reserved, 0 |
| 36 | 4 | CRC32C of header bytes 0..35 |

A zstd payload is one zstd frame. Payload limit: 256 MiB stored and raw.

A reader **skips** chunk types it does not know unless the critical flag is set, in which case it
refuses the file. This is how later minor versions add data.

## Trailer (24 bytes, finished files only)

| Offset | Size | Field |
| --- | --- | --- |
| 0 | 8 | `KNRTRAIL` |
| 8 | 8 | file offset of the INDX chunk header |
| 16 | 4 | reserved, 0 |
| 20 | 4 | CRC32C of bytes 0..19 |

## Chunk types

### `META` — metadata

UTF-8 JSON (see `ReplayMetadata`). The last META chunk in the file wins. Unknown keys must be
preserved by writers that rewrite metadata.

| Key | Meaning |
| --- | --- |
| `kind` | `RECORDING`, `BUFFER` or `CLIP` |
| `kinoraVersion`, `minecraftVersion`, `protocolVersion`, `loader`, `loaderVersion` | what wrote it |
| `mods` | `[{id, version}]` loaded when recording |
| `singleplayer`, `serverName`, `serverAddress`, `worldName`, `startDimension` | where |
| `player` | `{name, uuid}` |
| `resourcePacks` | enabled pack ids |
| `startedAtMillis` | wall-clock start |
| `startTick`, `endTick`, `durationNanos` | timeline extent; ticks before `startTick` are pre-roll |
| `complete`, `recovered`, `masked` | state flags |
| `sourceFileId` | for clips and buffers, the original file |
| `ticksPerSecond` | nominal recording tick rate (20) |

### `PKTS` — stream records

```
varlong firstOrdinal
varlong baseTick
varlong baseNanos
varint  count
count × {
  u8      kind
  varlong tickDelta     (non-negative)
  zigzag  nanosDelta
  varint  length
  bytes   payload
}
```

Ordinals number the records of the whole stream from 0, in order. Ticks never decrease. Record kinds:

| Kind | Name | Payload |
| --- | --- | --- |
| 1 | PACKET | `u8 phase` (1 configuration, 2 play), then the packet as its protocol codec encodes it (VarInt id, body) |
| 2 | CLIENT_STATE | `u8 type`, then type-specific data (section below) |
| 3 | SOUND | sound event (section below) |
| 4 | MARKER | `string name, i32 argb, string category, u8 source` |
| 5 | MOD_DATA | `varint track, u8 flags (bit 0 state), bytes` |
| 6 | STREAM_EVENT | `u8 code`, code-specific |

Readers skip kinds they do not know.

### `SNAP` — snapshot

```
varlong tick
varlong nanos
varlong resumeOrdinal
varint  count
count × {
  u8 entryType          0 inline, 1 reference
  inline:    u8 kind, varint length, bytes
  reference: varlong ordinal  (< resumeOrdinal)
}
```

Applying a snapshot: start a fresh session, apply the entries in order (inline records directly,
references by reading that record), then continue with the stream at `resumeOrdinal`. Inline records
take effect at the snapshot tick.

### `MODT` — mod data tracks

`varint count`, then per track `varint index, string id, varint version`. Several MODT chunks are
additive.

### `THMB` — thumbnail

`varlong tick`, then a PNG.

### `MARK` — marker table

Written when the file is finished: `varint count`, then per marker
`varlong tick, varlong nanos, string name, i32 argb, string category, u8 source`.
A file without one has its markers in MARKER records.

### `INDX` — index

```
varint count
count × {
  u32 type, varlong sequence, varlong offset, varint flags, varint storedLength,
  varlong firstTick, varlong lastTick, varlong firstOrdinal, varint recordCount
}
```

`firstTick`/`lastTick`/`firstOrdinal`/`recordCount` describe PKTS chunks; for SNAP they are the
snapshot tick (twice) and resume ordinal; for THMB the tick. The index lists every chunk except itself.

## CLIENT_STATE records (kinora-mc)

`u8 type` then:

| Type | Name | Data |
| --- | --- | --- |
| 1 | PLAYER | `varint entityId, f64 x, f64 y, f64 z, f32 yRot, f32 xRot, f32 yHeadRot, f32 yBodyRot, bool onGround, f32 fov, u8 cameraType` |
| 2 | EQUIPMENT | `varint entityId, varint count, count × (u8 slot, item stack via the play codec)` |
| 3 | SWING | `varint entityId, u8 hand` |
| 4 | VEHICLE | `varint entityId, f64 x, f64 y, f64 z, f32 yRot, f32 xRot` |
| 5 | IDENTITY | `varint entityId, uuid, string name` (first record of a session) |
| 6 | ENTITY_DATA | the player's synced values, all of them, encoded as a `ClientboundSetEntityDataPacket` (entity id, values). Written when any value changes (skin layers, pose, flags). |

## SOUND records (kinora-mc)

`string soundEvent, string soundFile, u8 category, f64 x, f64 y, f64 z, f32 volume, f32 pitch,
varint attenuationDistance, u8 flags (bit 0 relative, bit 1 looping, bit 2 local, bit 3 linear attenuation, bit 4 streamed),
varlong seed`

## Recovery

A file without a valid trailer is scanned from the header: each chunk is accepted if its header and
payload checksums hold and it decodes; on failure the reader searches forward for the next `KCHK`
that does. Recovery truncates any damaged tail and appends `META` (with `recovered: true`), `MARK`,
`INDX` and the trailer. Everything up to the last complete chunk survives a crash.

## Compatibility

* Major version changes are not readable by older readers; minor changes are.
* A replay records its Minecraft and protocol version. Kinora opens replays from other versions only
  after a warning, and does not promise they work.
