# shellcheck shell=bash
#
# cc12-artifact-name.sh — the ONE definition of a release artifact filename.
#
# CC12 (~/Admin-Manual/DOCS/CICD_CONVENTIONS/artifact-hygiene-stamped-or-cleaned.md):
#
#     <slug>-v<MAJOR.MINOR.BUILD>-<qualifier>.<ext>
#
# slug FIRST, version SECOND, qualifier LAST. Operator rationale (2026-07-14): a
# version-first name "reads as 'version 0.14.2 of… what?' and sorts before the project
# identity." The qualifier is what lets you decide keep-or-replace from `ls` alone, without
# opening the file.
#
# WHY THIS FILE EXISTS (I-26(c) — fix the system, not the instance):
# the pattern used to be retyped as a bare string literal in 13 places across
# build-{android,linux,windows,release}.sh. With no single definition it could not help but
# drift, and nothing checked the result — so seven of eight artifacts silently ended up as
# <slug>-<platform>-v<VERSION>-<qualifier> (platform before version) and the web bundle as
# <slug>-web-v<VERSION>.zip with no qualifier at all. Renaming those files alone would have
# left the mechanism that produced them fully intact.
#
# Two things therefore live here and nowhere else:
#   cc12_name        — construct a compliant name (the single definition)
#   cc12_assert_dist — refuse to finish a build that staged a non-compliant name
#
# The assert is the half that matters. A convention with no gate is a convention that drifts
# back the moment attention moves on.

# Canonical product slug. Both trees share the reverse-DNS id, so the tauri2 marker is part
# of the slug to keep the two products' artifacts distinguishable in one dist/.
CC12_SLUG="${CC12_SLUG:-recruiser}"

# cc12_name <version> <qualifier> <ext>
#   cc12_name 2.12.51257 android-release-signed apk
#     -> mba.robin.kintsugitarot-tauri2-v2.12.51257-android-release-signed.apk
cc12_name() {
  local version="$1" qualifier="$2" ext="$3"
  if [ -z "$version" ] || [ -z "$qualifier" ] || [ -z "$ext" ]; then
    echo "cc12_name: need <version> <qualifier> <ext>; got '$1' '$2' '$3'" >&2
    return 1
  fi
  printf '%s-v%s-%s.%s' "$CC12_SLUG" "$version" "$qualifier" "${ext#.}"
}

# cc12_path <dist_dir> <version> <qualifier> <ext>
cc12_path() {
  local dist="$1"; shift
  printf '%s/%s' "$dist" "$(cc12_name "$@")"
}

# cc12_assert_dist <dist_dir> <version>
#
# Fails (non-zero) if any artifact in <dist_dir> carrying <version> does not match the CC12
# shape. CC12 is explicit that an unstampable artifact "must not be left behind anywhere" —
# there is no third state — so a build that produced a non-compliant name has not succeeded.
#
# Only files for the version just built are checked: historical artifacts are superseded, and
# I-0 forbids rewriting them retroactively.
cc12_assert_dist() {
  local dist="$1" version="$2" bad=0 f base
  local want="^${CC12_SLUG//./\\.}-v${version//./\\.}-[A-Za-z0-9][A-Za-z0-9._-]*\.[A-Za-z0-9]+$"

  for f in "$dist"/*"$version"*; do
    [ -e "$f" ] || continue
    base="$(basename "$f")"
    if ! printf '%s' "$base" | grep -qE "$want"; then
      echo "[cc12] NON-COMPLIANT: $base" >&2
      bad=$((bad + 1))
    fi
  done

  if [ "$bad" -gt 0 ]; then
    cat >&2 <<EOF
[cc12] $bad artifact name(s) violate CC12.
[cc12]   required: ${CC12_SLUG}-v<VERSION>-<qualifier>.<ext>   (slug, then version, then qualifier)
[cc12]   e.g.      $(cc12_name "$version" android-release-signed apk)
[cc12] CC12: "If an artifact cannot be stamped, it must not be left behind anywhere."
[cc12] Construct names with cc12_name() from scripts/lib/cc12-artifact-name.sh — never a literal.
EOF
    return 1
  fi
  echo "[cc12] OK: every $version artifact in $dist matches <slug>-v<VERSION>-<qualifier>.<ext>"
}
