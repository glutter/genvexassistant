# Genvex Assistant - LLM Instructions

## Versioning Rules
- **Synchronized Versioning**: Always keep versions perfectly synced across BOTH configurations when introducing changes or bumping:
  1. `pom.xml` (`<version>X.YY</version>`)
  2. `ha_addon/config.json` (`"version": "X.YY"`)
- **Documentation**: Remember to update `ha_addon/CHANGELOG.md` with version notes and release summaries.
- **Local Building**: Do not use GitHub Actions. Always build locally using `./publish.sh`.