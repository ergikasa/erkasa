# contracts

`scene-catalog.json` is the scene contract between the three parts of erkasa:
which scene types exist, which fields each one fills, which visual styles may use it,
and the icons, poses, sounds and music moods the LLM may pick.

- **Source:** the backend (`SceneCatalog`, `SoundEffects`, `ScriptSchema`). Never edit the file by hand.
- **Checked by:** `SceneContractTests` (backend), `render-worker/src/test/contract.test.ts` and `frontend/src/test/scene-contract.test.ts`.
  If a name changes in one place only, one of these tests fails.

Update it after changing the backend catalog (PowerShell, from `backend/`):

```powershell
$env:CONTRACT_UPDATE="1"; .\mvnw.cmd test -Dtest=SceneContractTests; Remove-Item Env:CONTRACT_UPDATE
```

Then commit the file, and raise `SceneCatalog.CONTRACT_VERSION` when a name changed or a type was added.
Never remove a type: scripts saved in the database still use it.
