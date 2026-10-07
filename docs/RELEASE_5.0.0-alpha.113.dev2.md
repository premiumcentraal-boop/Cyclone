# Native Instagram starter skills and the Skills library

This Android owner-test candidate builds on alpha.112 and adds a ready-to-use Instagram example. It is not a published release. The unchanged PC components remain on alpha.112 and Glass on alpha.62.

## What owners see

The main Routines tab is now **Skills**, with **Apps / Skills / Routines** segments. Apps combines each app's available skills and routines, shows both counts, and puts apps with content first. Skills shows included tested routes and the owner's locally grounded skills. Added skills awaiting verification remain visible in Apps and search with an honest status label. Routines retains the existing routines, creation tools, triggers and Run behavior.

One **Search** field searches across all three kinds, independent of the selected segment, and groups its results into Apps, Skills and Routines. Home smart search also includes the starter skills and opens their native sheets. Marketplace is explicitly the place to get more skills; included starters open directly and require no install action.

## Included Instagram skills

| Skill | Reviewed behavior | Limit |
| --- | --- | --- |
| Prepare an Instagram post | Choose one photo, reach editor, enter caption, review; optional approved Share with one submission and profile verification | One photo publication tested; editor subtools, video, multiple images and alt text unverified |
| Set up an Instagram account | Complete details in Cyclone, follow mapped signup, secure password fill, native SMS retrieval, check signed-in profile | One phone-number signup tested; email and signed-in username-first variant not exercised end to end |
| Open my Instagram profile | Reach own profile and verify account header | Requires a signed-in account |
| Open Instagram profile editing | Reach field labels without changing values | Editor landing only |
| Search Instagram accounts | Enter query, select Accounts, check results | Other search categories unverified |
| Read Instagram comments | Read and scroll the current post's thread | No native clipboard-copy or reply guarantee |
| Open Instagram saved items | Reach saved category landings | Collection creation and Audio unverified |
| Show my Instagram profile QR | Display the sharing/QR landing | No sending, downloading or scanning |
| Open Instagram settings | Reach Settings and activity | No preference changes |
| Check Instagram message requests | Reach Requests and Hidden requests | Empty folders tested; actual request handling unverified |

These are portable live-screen guides, not coordinate macros or compiled replay proofs. The original experiment used Instagram 449.0.0.52.84 on one Pixel 8. Each skill carries its route, completion checks, evidence and limits. Cyclone prefers the phone's learned map, re-observes page changes and handles real account, language and version differences. No universal Android or 100% success claim is made. Reel viewing/discovery and clipboard extraction remain outside this starter set until verified.

## Account setup before phone execution

The native sheet derives one input row per non-verification field from the complete local map or packaged starter map. The packaged phone flow has phone number, password, birthday, full name and username rows. It validates required values and formats, then requires the owner to review the details before Start. SMS codes arrive during the run and are not requested or saved in the form.

The password is masked, held in non-saveable form state, then passed to the existing one-shot secret-fill mechanism. It is bound to this mission and Instagram's package, expires after 30 minutes, and is cleared on failed admission, task completion or form dismissal. It is excluded from ordinary recipe inputs and model context and does not overwrite an existing vault password. Other form values are used for this run; this UI adds no saved personal defaults.

Starting the phone form does not approve Instagram's terms. Cyclone asks at the actual terms/account-creation boundary. Existing Glass setup runs retain their established approval contract. Post skills default to Review only and retain the runtime Share/Post gate even when started through Command Center.

## Validation and acceptance

Required checks are Android JVM tests, lint, release assembly, repository/product/security guards and coherent version metadata. Unit checks cover grouped search, app counts, catalog validity, review-only posting, schema preflight, value filtering, terms approval, and password binding, expiry, one-use, rejection and cleanup. Existing routines and saved skills keep their prior stores and execution paths.

Physical acceptance is separate: inspect the new segments, Instagram counts, grouped search, native sheets and signup validation on the connected phone; run a harmless profile navigation with an owner-approved experiment model. Do not create another account or publish another post solely to check this UI. Candidate identity is 5.0.0-alpha.113.dev2, Android code 262. Distribution uses the protected signed-owner-test workflow; public publication remains disabled.

The first phone candidate revealed a false SEND prompt when merely opening a post skill. Dev2 tags only first-party skill-details cards as navigation. The canonical click path checks the observed Cyclone package and actual activation node; model/PC parameters, external app lookalikes, input fields and untagged Run/Share buttons retain their existing policy. Regression tests cover these boundaries.
