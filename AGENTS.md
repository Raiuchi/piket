# Release requirements

Every APK release must also publish the matching browser app to Raiuchi/piket-web main. Run `node tools/sync-web.mjs ../piket-web`, run the web checks and reliability scenarios, commit and push the web repository, and verify GitHub Pages deployment. Do not claim a coordinated release until both publications are verified. Preserve browser limitations in documentation; Android-only services cannot run in a browser.

Keep README diagnostics instructions current. The APK badge uses the latest GitHub release dynamically. Update README on the default branch as well when working on a release branch.
