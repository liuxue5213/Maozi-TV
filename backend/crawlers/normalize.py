"""Channel name normalisation.

Different m3u sources name the same channel very differently
(e.g. "CCTV-1", "CCTV1", "CCTV-1 综合", "央视一套"). We collapse these to a
single canonical key so the source manager merges their stream URLs together.
"""

import re
import unicodedata
from typing import Dict

# Alias map: maps a cleaned/normalised name to a canonical display name.
# Keys are matched case-insensitively after basic cleaning.
CHANNEL_ALIASES: Dict[str, str] = {
    # CCTV
    "cctv1": "CCTV-1",
    "cctv2": "CCTV-2",
    "cctv3": "CCTV-3",
    "cctv4": "CCTV-4",
    "cctv5": "CCTV-5",
    "cctv6": "CCTV-6",
    "cctv7": "CCTV-7",
    "cctv8": "CCTV-8",
    "cctv9": "CCTV-9",
    "cctv10": "CCTV-10",
    "cctv11": "CCTV-11",
    "cctv12": "CCTV-12",
    "cctv13": "CCTV-13",
    "cctv14": "CCTV-14",
    "cctv15": "CCTV-15",
    "cctv16": "CCTV-16",
    "cctv17": "CCTV-17",
    "央视一套": "CCTV-1",
    "央视二套": "CCTV-2",
    "央视三套": "CCTV-3",
    "央视四套": "CCTV-4",
    "央视五套": "CCTV-5",
    "央视新闻": "CCTV-13",
    "央视少儿": "CCTV-14",
    "中央电视台综合频道": "CCTV-1",
    "中央电视台财经频道": "CCTV-2",
    # Hunan
    "湖南卫视": "湖南卫视",
    "芒果tv": "湖南卫视",
    # Beijing
    "北京卫视": "北京卫视",
    "btv": "北京卫视",
}


def normalize_channel_name(name: str) -> str:
    """Return a canonical key for a channel name.

    The returned key is used ONLY for grouping/dedup; the original display
    name is preserved when creating the Channel row.
    """
    if not name:
        return ""
    # Full-width → half-width (e.g. ＣＣＴＶ１ → CCTV1)
    name = unicodedata.normalize("NFKC", name)
    # Lowercase + trim
    cleaned = name.strip().lower()
    # Remove separators that vary between sources: spaces, - _ . ·
    cleaned = re.sub(r"[\s\-_\.·•・]+", "", cleaned)
    # Collapse parenthetical suffixes like "CCTV1(综合)" -> "cctv1"
    cleaned = re.sub(r"[\(（].*?[\)）]", "", cleaned)
    # Chinese synonyms for "HD" / "标清" — keep resolution out of the key so
    # "CCTV1高清" and "CCTV1HD" merge with "CCTV1".
    cleaned = re.sub(r"(高清|超清|hd|fhd|uhd|4k|8k|标清|sd)", "", cleaned)
    # Remove trailing "台"/"卫视" qualifier once for grouping is risky (would
    # merge 湖南卫视/湖南经视), so we leave those in.
    cleaned = cleaned.strip()

    # Apply alias table on the cleaned key
    if cleaned in CHANNEL_ALIASES:
        return CHANNEL_ALIASES[cleaned].lower()

    return cleaned


def display_name_for(name: str) -> str:
    """Pick a nice display name, preferring the canonical alias if known."""
    if not name:
        return ""
    cleaned = name.strip()
    folded = normalize_channel_name(cleaned)
    for canonical in CHANNEL_ALIASES.values():
        if canonical.lower() == folded:
            return canonical
    return cleaned
