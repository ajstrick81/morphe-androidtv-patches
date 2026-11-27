# Skills

[Agent Skills](https://docs.claude.com/en/docs/agents-and-tools/agent-skills/overview) for writing and maintaining Morphe patches.

| Skill | For | Where |
|---|---|---|
| [`morphe-patch-authoring`](morphe-patch-authoring/SKILL.md) | Anyone writing Morphe patches for any Android app. Fingerprint, patch, drift and testing rules learned in this repo, with nothing repo-specific. | `skills/` (this folder) |
| [`morphe-atv-patch`](../.claude/skills/morphe-atv-patch/SKILL.md) | Working in this repo. Uses the seam-map drift checker, `AppCompatibilities`, the docs layout and the release conventions. Loads automatically in Claude Code sessions here. | `.claude/skills/` |

## Install `morphe-patch-authoring`

Copy the folder into your skills directory:

```bash
# Claude Code, all projects
cp -r skills/morphe-patch-authoring ~/.claude/skills/

# or one project only
cp -r skills/morphe-patch-authoring <your-repo>/.claude/skills/
```

It is deliberately kept out of `.claude/skills/` so it doesn't load alongside `morphe-atv-patch` and give two sets of instructions for the same task.

Licensed GPL-3.0, like the rest of this repo.
