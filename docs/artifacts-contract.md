# Generated artifacts and source-of-truth contract

Some tracked files are generated outputs. Change their authoritative inputs
and run the generator instead of editing the outputs by hand. Otherwise, the
next generation may overwrite the edit or CI may fail.

## Sources of truth

- Application, native, and generator source files are maintained directly.
- Packaged language-model assets are generated from their sources by
  `tools/prepare_assets.py`.

## Generated files

- `language-models/models.elqm` and the Lingua bridge are generated or
  verified by `tools/prepare_assets.py`.

## Updating generated data

For packaged language-model assets, use
`python3 tools/prepare_assets.py --verify` to check reproducibility.

Generated files should match their inputs deterministically. Include the
source changes and reviewed generated outputs together when a generated
artifact changes.