#!/usr/bin/env python3
"""Read the highlight markers a DJI Osmo Action stores inside a video file and write them as CSV.

Usage:  python3 dji_markers.py /path/to/clip.MP4 [output.csv]

The camera stores its markers (from its side button, or dropped by BiciCAM from the Karoo) in the
HighlightMarkers tag of the file, in seconds from the start of the clip. Requires exiftool.
Checked with an Osmo Action 6 clip holding six markers dropped from the Karoo.

Uso (español):  python3 dji_markers.py /ruta/al/clip.MP4 [salida.csv]
Lee los marcadores que la cámara guarda dentro del vídeo y los escribe como CSV. Necesita exiftool.
"""
import csv
import subprocess
import sys


def markers(clip: str) -> list[float]:
    output = subprocess.run(
        ["exiftool", "-s3", "-HighlightMarkers", clip], capture_output=True, text=True, check=True
    ).stdout.strip()
    return [float(value) for value in output.replace(",", " ").split()] if output else []


def main() -> None:
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    seconds = markers(sys.argv[1])
    rows = [(i, s, "%02d:%02d:%02d" % (s // 3600, s % 3600 // 60, s % 60)) for i, s in enumerate(seconds, 1)]
    target = open(sys.argv[2], "w", newline="") if len(sys.argv) > 2 else sys.stdout
    writer = csv.writer(target)
    writer.writerow(["marker", "clip_second", "timecode"])
    writer.writerows(rows)
    if target is not sys.stdout:
        target.close()
        print(f"{len(rows)} markers written to {sys.argv[2]}")


if __name__ == "__main__":
    main()
