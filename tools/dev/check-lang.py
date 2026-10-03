"""Lists translation keys used in Kinora's Java sources that en_us.json lacks.

Literal keys ("kinora.x.y") are checked directly. Keys built from a prefix plus an enum name
("kinora.rig." + mode) are expanded from the enum values given in DYNAMIC below.
Usage: python tools/dev/check-lang.py
"""
import json
import pathlib
import re
import sys

root = pathlib.Path(__file__).resolve().parents[2]
lang = json.loads((root / "kinora-mc/src/main/resources/assets/kinora/lang/en_us.json").read_text(encoding="utf-8"))
used = set()
for path in (root / "kinora-mc/src/main/java").rglob("*.java"):
    text = path.read_text(encoding="utf-8")
    used.update(re.findall(r'"((?:kinora|key\.kinora)\.[a-z0-9_.]+[a-z0-9_])"', text))

DYNAMIC = {
    "kinora.interp.": ["hold", "linear", "catmull_rom", "centripetal", "bezier"],
    "kinora.easing.": ["linear", "sine_in", "sine_out", "sine_in_out", "quad_in", "quad_out", "quad_in_out", "cubic_in",
                       "cubic_out", "cubic_in_out", "expo_in", "expo_out", "expo_in_out", "custom"],
    "kinora.rig.": ["path", "follow", "orbit"],
    "kinora.shake.": ["steady", "handheld", "jog", "earthquake", "drone", "custom"],
    "kinora.hud.camera.": ["free", "spectate", "orbit", "path"],
    "kinora.inspector.title_": ["top", "middle", "bottom"],
    "kinora.render.projection.": ["normal", "equirectangular", "cubemap", "stereo_side_by_side", "stereo_top_bottom"],
    "kinora.render.sound.": ["off", "follow", "preserve", "mute"],
    "kinora.track.": ["camera.position", "camera.rotation", "camera.roll", "camera.fov", "time", "lookat.weight",
                      "shake.intensity", "orbit.angle", "orbit.radius", "orbit.height", "dof.focus", "dof.aperture",
                      "grade.exposure", "grade.contrast", "grade.saturation", "grade.temperature", "grade.tint",
                      "fx.vignette", "fx.grain", "fx.chromatic", "fx.bloom", "fx.letterbox", "world.time", "world.weather"],
}
for prefix, names in DYNAMIC.items():
    used.discard(prefix.rstrip("."))
    used.discard(prefix)
    used.update(prefix + n for n in names)

missing = sorted(k for k in used if k not in lang and not k.endswith(".") and k != "kinora.replay" and not k.startswith("kinora.dev."))
for k in missing:
    print(k)
sys.exit(1 if missing else 0)
