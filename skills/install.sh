#!/usr/bin/env bash
# Installs the AgentNav ACP custom skills into ~/.claude/skills/ so claude can invoke
# them via `/skill-name` slash commands. Re-run to update.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEST="${HOME}/.claude/skills"
mkdir -p "$DEST"

for skill_dir in "$SCRIPT_DIR"/*/; do
    name="$(basename "$skill_dir")"
    target="$DEST/$name"
    echo "→ Installing $name to $target"
    rm -rf "$target"
    cp -r "$skill_dir" "$target"
    # Make any .py / .sh executable
    find "$target" -type f \( -name "*.py" -o -name "*.sh" \) -exec chmod +x {} \;
done

echo "✓ Installed $(ls -1 "$SCRIPT_DIR" | grep -v 'install\.sh' | wc -l) skill(s) into $DEST"
echo "  Restart Claude Code or run \`claude\` once for it to pick them up."
