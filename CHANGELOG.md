# [1.17.0](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.16.0...v1.17.0) (2026-07-31)


### Features

* **pluto:** remove VOD ads via DASH period surgery (Skip ads Hook 5) ([#80](https://github.com/ajstrick81/morphe-androidtv-patches/issues/80)) ([901d617](https://github.com/ajstrick81/morphe-androidtv-patches/commit/901d6175d3736d2889b3b67eb17fab67a17d6bfe))

# [1.16.0](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.15.0...v1.16.0) (2026-07-30)


### Features

* **primevideo-native:** suppress TV-show preroll/mid-roll ads (regolith) ([35c4725](https://github.com/ajstrick81/morphe-androidtv-patches/commit/35c4725aa3d5f3052f602f332700f202f6aa6a26))

# [1.15.0](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.14.0...v1.15.0) (2026-07-27)


### Bug Fixes

* **primevideo-native:** skip decompression chunks to stop CURL 61 corruption ([9787c81](https://github.com/ajstrick81/morphe-androidtv-patches/commit/9787c8170e44eec3ce9d19e10b43351290139dbf))


### Features

* **primevideo-native:** working GOT/PLT import hook for in-process ad strip ([516017d](https://github.com/ajstrick81/morphe-androidtv-patches/commit/516017d20a21bd18c252d602cf783442003434d4))

# [1.14.0](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.13.0...v1.14.0) (2026-07-22)


### Features

* **disney:** add 26.12.1+rc1 to compatible targets ([fb8f3f4](https://github.com/ajstrick81/morphe-androidtv-patches/commit/fb8f3f4162e6c31155ddb72dc0a87aaeb8f8f39d))

# [1.13.0](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.12.3...v1.13.0) (2026-07-20)


### Features

* **tubi:** bump target to 10.28.5000 ([0050cc8](https://github.com/ajstrick81/morphe-androidtv-patches/commit/0050cc8f4ff2b27da668abb315ff0fe2e897cd75))

## [1.12.3](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.12.2...v1.12.3) (2026-07-18)


### Bug Fixes

* **hbomax:** support 7.7.0.78 and derive Patch 2 register dynamically ([d35a8b0](https://github.com/ajstrick81/morphe-androidtv-patches/commit/d35a8b043e1a2dacd69b17de7640ebc9a339b694))

## [1.12.2](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.12.1...v1.12.2) (2026-07-16)


### Bug Fixes

* **dns:** leave the zoar/hercule weblab blocks off pending proof ([21bb23c](https://github.com/ajstrick81/morphe-androidtv-patches/commit/21bb23c149968b2e6ba233a33fcabf2b178704fd)), closes [#58](https://github.com/ajstrick81/morphe-androidtv-patches/issues/58)
* **dns:** stop blocking aiv-delivery.net — it breaks playback ([#58](https://github.com/ajstrick81/morphe-androidtv-patches/issues/58)) ([6a8217c](https://github.com/ajstrick81/morphe-androidtv-patches/commit/6a8217c1ba874393abfc71c80a80dd70a371d7bf))

## [1.12.1](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.12.0...v1.12.1) (2026-07-15)


### Bug Fixes

* **dns:** correct malformed modifier separators and add a rule linter ([ad3c4fd](https://github.com/ajstrick81/morphe-androidtv-patches/commit/ad3c4fd9635ff3ab1e6618b6624ec35691b35ff7))

# [1.12.0](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.11.0...v1.12.0) (2026-07-12)


### Features

* **disney:** cover new DMP SGAI/SSAI VOD ad insertion pipeline ([57d0b76](https://github.com/ajstrick81/morphe-androidtv-patches/commit/57d0b76f07c498087843a39247a758cc44cf532a))

# [1.11.0](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.10.0...v1.11.0) (2026-07-12)


### Features

* **vix:** support 4.47.2_tv; re-anchor Innovid fingerprint on string ([#54](https://github.com/ajstrick81/morphe-androidtv-patches/issues/54)) ([37e3ec6](https://github.com/ajstrick81/morphe-androidtv-patches/commit/37e3ec6659a39befbd54cee9bf01bdb687f07c6c))

# [1.10.0](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.9.0...v1.10.0) (2026-07-04)


### Features

* **peacock:** DAI-swap ad suppression + Clone and Disable auto-updates ([2969316](https://github.com/ajstrick81/morphe-androidtv-patches/commit/296931651c3d50c49e88190286d087a23e2a5f32))
* **pluto:** add Clone and Disable auto-updates patches ([4358314](https://github.com/ajstrick81/morphe-androidtv-patches/commit/4358314cab49bd34a84fa17517f3394a49ea79d3))

# [1.9.0](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.8.0...v1.9.0) (2026-07-03)


### Features

* **pluto:** add certificate-pinning override for HTTPS inspection ([723ceeb](https://github.com/ajstrick81/morphe-androidtv-patches/commit/723ceebb3ee0987efb0991478b902b467bcd2076))
* **pluto:** strip ad-break timeline to remove VOD ads ([16cc76b](https://github.com/ajstrick81/morphe-androidtv-patches/commit/16cc76b67468d692ef0c4c1f7b5789d8cc79883b))
* **pluto:** verify ad hooks against dex, add clickable-ads suppression ([575612f](https://github.com/ajstrick81/morphe-androidtv-patches/commit/575612f34caf006a040df1e200b02f03f31184c3))

# [1.8.0](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.7.0...v1.8.0) (2026-07-02)


### Bug Fixes

* **primevideo:** block additional Volley ad hosts ([b47e706](https://github.com/ajstrick81/morphe-androidtv-patches/commit/b47e706d8597ba36ad66a2385a2e1ec07a123472))


### Features

* **primevideo:** add Clone Prime Video side-by-side install patch ([0ebea56](https://github.com/ajstrick81/morphe-androidtv-patches/commit/0ebea56de302d986a3e559a224c7e5e5affdab3b))

# [1.7.0](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.6.0...v1.7.0) (2026-06-27)


### Bug Fixes

* **dns:** match akamaized.net safe-harbor host by concatenated prefix ([b883df7](https://github.com/ajstrick81/morphe-androidtv-patches/commit/b883df7853da554f75e0a184bafdb08988ddf709))


### Features

* **primevideo:** remove GMB send diagnostic patch, add DNS rules from live testing ([48b744b](https://github.com/ajstrick81/morphe-androidtv-patches/commit/48b744bb136a49ecdaa8c7d3a5ed11e618e73d60))

# [1.6.0](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.5.7...v1.6.0) (2026-06-26)


### Bug Fixes

* **vix:** retarget Innovid hook to mount method, drop broken FreeWheel hook ([33cefba](https://github.com/ajstrick81/morphe-androidtv-patches/commit/33cefba404703be7f7da0cf02c25e8f3aab63206))


### Features

* add GMB send-direction diagnostic patch (debug-only) ([b0552cb](https://github.com/ajstrick81/morphe-androidtv-patches/commit/b0552cb7f84a74b455bef5473b781328164c5fae))
* **primevideo dns:** v4.2 — add hercule trigger after live-session validation ([82c94cd](https://github.com/ajstrick81/morphe-androidtv-patches/commit/82c94cd445750966a73aad2e8d3e2efe1a8898c1))
* **primevideo dns:** v4.3 — optional general-tracker section (triaged) ([4182df0](https://github.com/ajstrick81/morphe-androidtv-patches/commit/4182df0c6594e966a596f49516b99a17c6b037e1))
* **primevideo:** add evidence-graded DNS ad-suppression list (dual-layer) ([c55bb0e](https://github.com/ajstrick81/morphe-androidtv-patches/commit/c55bb0e1e10ee82529a5b5102987c24b02f0582d))
* **primevideo:** block getVideoAds path on dual-use playback host ([940b91a](https://github.com/ajstrick81/morphe-androidtv-patches/commit/940b91aff4a56a347e44eaf498d7351dfb8f3cdd))
* **primevideo:** expand Volley ad-host block list from v3.6 session diff ([6f05cb5](https://github.com/ajstrick81/morphe-androidtv-patches/commit/6f05cb5a9922a24f9f50759083287ee571895f9e))
* **vix:** add "Override certificate pinning" companion patch ([1fcdccf](https://github.com/ajstrick81/morphe-androidtv-patches/commit/1fcdccf651d01cc1ee9e3ba2ddf45154b796fc1a))
* **vix:** block LuraPlayer linear (client-side VAST) ad-break scheduler ([1360354](https://github.com/ajstrick81/morphe-androidtv-patches/commit/1360354a9275a2fbcd1d363114876c5e9bef6c04))

## [1.5.7](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.5.6...v1.5.7) (2026-06-26)


### Bug Fixes

* **peacock:** remove blocking Thread.sleep() from ad-block interceptors ([f0d441d](https://github.com/ajstrick81/morphe-androidtv-patches/commit/f0d441de63adb39c33afc78ab1e28a7cbca1b5de))
* **peacock:** rewrite wrapClient() to use named class, not anonymous ([188b354](https://github.com/ajstrick81/morphe-androidtv-patches/commit/188b354c6551e5a3b958f526b84735ed5926001f))
* **peacock:** roll back to stable Layers 1-8 + fixed Layer 7 baseline for main ([c54992e](https://github.com/ajstrick81/morphe-androidtv-patches/commit/c54992e342e8e2bf51f5cd95454cd7219e0aaa2c))

## [1.5.6](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.5.5...v1.5.6) (2026-06-26)


### Bug Fixes

* **peacock:** fix Layer 7 VerifyError launch crash + re-add Layers 9-11 ([#32](https://github.com/ajstrick81/morphe-androidtv-patches/issues/32)) ([c5257dd](https://github.com/ajstrick81/morphe-androidtv-patches/commit/c5257dde17b6ae5b9d5fa6e9025d14788abe5337))

## [1.5.5](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.5.4...v1.5.5) (2026-06-26)


### Bug Fixes

* **peacock:** roll back to known-good Layers 1-8; remove launch-breaking Layers 9-10 ([#31](https://github.com/ajstrick81/morphe-androidtv-patches/issues/31)) ([3641c54](https://github.com/ajstrick81/morphe-androidtv-patches/commit/3641c54c2387b143168e7a199ec1c81cb7a86d58))

## [1.5.4](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.5.3...v1.5.4) (2026-06-25)


### Bug Fixes

* **peacock:** revert Layer 11 + SAS/VAC matcher that broke playback on v7.5.100 ([#29](https://github.com/ajstrick81/morphe-androidtv-patches/issues/29)) ([#30](https://github.com/ajstrick81/morphe-androidtv-patches/issues/30)) ([09f5c64](https://github.com/ajstrick81/morphe-androidtv-patches/commit/09f5c64a0b33569779e32d9a1fe55903980ca757)), closes [#27](https://github.com/ajstrick81/morphe-androidtv-patches/issues/27)

## [1.5.3](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.5.2...v1.5.3) (2026-06-25)


### Bug Fixes

* **peacock:** Layer 11 media-client coverage + SAS/VAC shard matching ([#27](https://github.com/ajstrick81/morphe-androidtv-patches/issues/27)) ([a132655](https://github.com/ajstrick81/morphe-androidtv-patches/commit/a132655743cff6cdf56e7ff65be3150952544759))

## [1.5.2](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.5.1...v1.5.2) (2026-06-25)


### Bug Fixes

* **peacock:** DNS-independent ad suppression — Sky SDK addon OkHttp client + New Relic agent kill ([#26](https://github.com/ajstrick81/morphe-androidtv-patches/issues/26)) ([5e592a4](https://github.com/ajstrick81/morphe-androidtv-patches/commit/5e592a4a23464c7ed6492cdd69b488f98beb06d8))

## [1.5.1](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.5.0...v1.5.1) (2026-06-23)


### Bug Fixes

* **mlbtv:** re-enable SSAI/DAI/TXXX blocking patches alongside overlay ([4cfcf7c](https://github.com/ajstrick81/morphe-androidtv-patches/commit/4cfcf7c4b3a22a1352e87f25a250ee05c8f5d0a3))

# [1.5.0](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.107...v1.5.0) (2026-06-23)


### Features

* replace MLB At Bat SSAI/DAI blocking with ad-break overlay ([adc3d3f](https://github.com/ajstrick81/morphe-androidtv-patches/commit/adc3d3fa802a43c494e487e28472a0c40a26899b))

## [1.4.107](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.106...v1.4.107) (2026-06-23)


### Bug Fixes

* **primevideo:** replace non-existent allowAutoUpdates attribute with versionCode bump ([e758753](https://github.com/ajstrick81/morphe-androidtv-patches/commit/e75875319b5831a3da95b6d30758b47934020a29))

## [1.4.106](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.105...v1.4.106) (2026-06-23)


### Bug Fixes

* push new version with changes to Update Fingerprints.kt ([3775332](https://github.com/ajstrick81/morphe-androidtv-patches/commit/37753320a316f5e351a0cf5e628fc1ca29d06f39))

## [1.4.105](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.104...v1.4.105) (2026-06-23)


### Bug Fixes

* Peacock Layer 7 constructor coverage + HBO Max 7.5.0.73 support ([#21](https://github.com/ajstrick81/morphe-androidtv-patches/issues/21)) ([b4e862a](https://github.com/ajstrick81/morphe-androidtv-patches/commit/b4e862af3b8e974d485c7e9634f08210ec52d647))

## [1.4.104](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.103...v1.4.104) (2026-06-22)


### Bug Fixes

* trigger release for Peacock ad-leak fixes and HBO Max 7.5.0.73 support ([dd1ba7b](https://github.com/ajstrick81/morphe-androidtv-patches/commit/dd1ba7bb04fe87836ca69c6565b9ac2020ecd3be))

## [1.4.103](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.102...v1.4.103) (2026-06-22)


### Bug Fixes

* push new version while Updating proguard rules to include enforceAdBlock ([54e320a](https://github.com/ajstrick81/morphe-androidtv-patches/commit/54e320ac7d5f325770a1a7b02fd72a74d3b8da02))

## [1.4.102](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.101...v1.4.102) (2026-06-22)


### Bug Fixes

* push new version with Update Gradle job ID in release workflow ([2c014ff](https://github.com/ajstrick81/morphe-androidtv-patches/commit/2c014ff9876db633525baa7c9ba6b994b96a1df7))

## [1.4.101](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.100...v1.4.101) (2026-06-22)


### Bug Fixes

* push new version Update SkipAdsPatch.java ([cba3509](https://github.com/ajstrick81/morphe-androidtv-patches/commit/cba350941e1788d31ffb4c7d54c7fce2d0f12945))

## [1.4.100](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.99...v1.4.100) (2026-06-22)


### Bug Fixes

* gradlew clean: Reorder Gradle commands in release workflow ([8d93490](https://github.com/ajstrick81/morphe-androidtv-patches/commit/8d93490bae60ba7eb543fc41a451a3f3a3f7e391))

## [1.4.99](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.98...v1.4.99) (2026-06-22)


### Bug Fixes

* stale cache - force Update SkipAdsPatch.java ([55d678b](https://github.com/ajstrick81/morphe-androidtv-patches/commit/55d678b79b9216cc4536566a60a69c72bd58ba24))

## [1.4.98](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.97...v1.4.98) (2026-06-22)


### Bug Fixes

* Implement ad blocking in Volley network layer ([9bc512b](https://github.com/ajstrick81/morphe-androidtv-patches/commit/9bc512b9d3e2b327b59171a3343dae706ad0f3fe))
* push new version by Adding Volley dependency for Prime Video integration ([bc789ef](https://github.com/ajstrick81/morphe-androidtv-patches/commit/bc789ef5cae6bb72a4ce9b8390829abbeb72dc6b))

## [1.4.97](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.96...v1.4.97) (2026-06-21)


### Bug Fixes

* Add extendWith call in SkipAdsPatch.kt ([f0abe0d](https://github.com/ajstrick81/morphe-androidtv-patches/commit/f0abe0d531c519426610de3c0e6b8bc25480c9da))
* Update dependencies in build.gradle.kts ([be68275](https://github.com/ajstrick81/morphe-androidtv-patches/commit/be6827510874c3a39f05f86bd2c6c9d133b6e88b))
* Update Gradle wrapper to version 9.5.0 ([7fbf2d7](https://github.com/ajstrick81/morphe-androidtv-patches/commit/7fbf2d7471ef75333df59ecf5483e6ac66bc2823))
* Update Prime Video extension patch for merging DEX ([8a93773](https://github.com/ajstrick81/morphe-androidtv-patches/commit/8a93773fbff409f08929ff9e45022d66596e63be))

## [1.4.96](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.95...v1.4.96) (2026-06-21)


### Bug Fixes

* Update app.morphe.patches plugin version to 1.3.2 ([a625d8f](https://github.com/ajstrick81/morphe-androidtv-patches/commit/a625d8f6aacd5b541ee958c5f304039f68982ba5))

## [1.4.95](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.94...v1.4.95) (2026-06-21)


### Bug Fixes

* Enhance logging for ad group stripping methods ([b1a7381](https://github.com/ajstrick81/morphe-androidtv-patches/commit/b1a7381761476bc7644d7527f11c6a18b0f8fde0))

## [1.4.94](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.93...v1.4.94) (2026-06-21)


### Bug Fixes

* Refactor SkipAdsPatch documentation and remove seek method ([20cf0bd](https://github.com/ajstrick81/morphe-androidtv-patches/commit/20cf0bd5f59af60f09d85311fd50d49eb015a9b5))

## [1.4.93](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.92...v1.4.93) (2026-06-21)


### Bug Fixes

* push new version via Update ParamountPatch for v16.12.0 compatibility ([8ae32cc](https://github.com/ajstrick81/morphe-androidtv-patches/commit/8ae32ccd5070f2ce69841e3de2947819f49b55fc))

## [1.4.92](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.91...v1.4.92) (2026-06-21)


### Bug Fixes

* push new version via Refine skipAdsPatch description and add comments ([f92dcc3](https://github.com/ajstrick81/morphe-androidtv-patches/commit/f92dcc3278094e1ffc86431b4cafffc7bc0f10ce))

## [1.4.91](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.90...v1.4.91) (2026-06-21)


### Bug Fixes

* Refactor URL blocking logic in PeacockWebViewHelper ([beac284](https://github.com/ajstrick81/morphe-androidtv-patches/commit/beac284f95904045745a59d94dfd26b8b1a4d524))

## [1.4.90](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.89...v1.4.90) (2026-06-21)


### Bug Fixes

* Refactor ad-break fetch coroutine handling ([7f1bb26](https://github.com/ajstrick81/morphe-androidtv-patches/commit/7f1bb26b6b2ddc3e39fb56c671383c4146295ad6))

## [1.4.89](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.88...v1.4.89) (2026-06-20)


### Bug Fixes

* Refactor SkipAdsPatch by removing unused hooks ([e1c3302](https://github.com/ajstrick81/morphe-androidtv-patches/commit/e1c3302bf7751b18a96df444ea3c7a5740fc04e7))
* Remove unnecessary closing parentheses in Fingerprints.kt ([8f211f3](https://github.com/ajstrick81/morphe-androidtv-patches/commit/8f211f3c237f40f48280af5a332b4c63127bd558))

## [1.4.88](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.87...v1.4.88) (2026-06-20)


### Bug Fixes

* Implement fingerprints for ad playback and metrics ([65a6878](https://github.com/ajstrick81/morphe-androidtv-patches/commit/65a6878006cbddd90b7e7b953b82c65ce1e8c865))

## [1.4.87](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.86...v1.4.87) (2026-06-20)


### Bug Fixes

* Revise ad suppression fingerprints for Fox One ([dad7e6f](https://github.com/ajstrick81/morphe-androidtv-patches/commit/dad7e6fcd07829e5abeee1239fc8264bdc26549a))

## [1.4.86](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.85...v1.4.86) (2026-06-20)


### Bug Fixes

* push new version with Update ad suppression patch for Yospace SSAI ([38d9168](https://github.com/ajstrick81/morphe-androidtv-patches/commit/38d916832cea3945eced94a7f5b6de83879ae63d))

## [1.4.85](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.84...v1.4.85) (2026-06-19)


### Bug Fixes

* Refactor metadata handling and patch DAI events ([ec8c6cc](https://github.com/ajstrick81/morphe-androidtv-patches/commit/ec8c6cc9d17d6617f80c4359616d56cb0afac6ed))

## [1.4.84](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.83...v1.4.84) (2026-06-19)


### Bug Fixes

* Refactor TXXX metadata dispatch handling ([520358e](https://github.com/ajstrick81/morphe-androidtv-patches/commit/520358ef3c8b6bfa0e82fa29da39109ee4218cba))

## [1.4.83](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.82...v1.4.83) (2026-06-18)


### Bug Fixes

* push new version: Enhance documentation for ad delivery and SSAI handling ([8fe99d7](https://github.com/ajstrick81/morphe-androidtv-patches/commit/8fe99d78ad8a2405f9a81ef98829b5cbfba53a12))
* Refactor ad delivery architecture comments in AtBatFingerprints.kt ([84da6e7](https://github.com/ajstrick81/morphe-androidtv-patches/commit/84da6e776d86f759931d49c292a3727b80535699))

## [1.4.82](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.81...v1.4.82) (2026-06-18)


### Bug Fixes

* Morphe patch error: Refactor FoxPlayerClearVodAdsFingerprint definition ([c3edc0c](https://github.com/ajstrick81/morphe-androidtv-patches/commit/c3edc0c9306ee724d4bab477b240bdf3ddb9213c))

## [1.4.81](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.80...v1.4.81) (2026-06-18)


### Bug Fixes

* Refactor patches and update fingerprint comments ([8bee5e3](https://github.com/ajstrick81/morphe-androidtv-patches/commit/8bee5e3d66f4d33d79f31fc9e51e428a126e1257))

## [1.4.80](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.79...v1.4.80) (2026-06-17)


### Bug Fixes

* Refactor MLB At Bat ad patches for clarity and functionality ([42f0d5a](https://github.com/ajstrick81/morphe-androidtv-patches/commit/42f0d5a67c56ab8b7f9cf3f51a75bfd9257d6c93))
* Refactor MLB At Bat ad suppression patches ([0532f64](https://github.com/ajstrick81/morphe-androidtv-patches/commit/0532f6487570ef419f180f5043f8cc82c412497b))

## [1.4.79](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.78...v1.4.79) (2026-06-17)


### Bug Fixes

* Refactor comments and patch descriptions in AtBatPatch ([aafcfe5](https://github.com/ajstrick81/morphe-androidtv-patches/commit/aafcfe5b8514d0408bf2dc7b01e85cf697db2a78))

## [1.4.78](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.77...v1.4.78) (2026-06-17)


### Bug Fixes

* Refactor ad break logic and update comments ([f8e340e](https://github.com/ajstrick81/morphe-androidtv-patches/commit/f8e340e2e48b415e83c392e4a608b9c3862c725a))

## [1.4.77](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.76...v1.4.77) (2026-06-17)


### Bug Fixes

* Update patch notes and adjust return types for suspend functions ([c8eb1f5](https://github.com/ajstrick81/morphe-androidtv-patches/commit/c8eb1f5cfc5ce86c0669671b3e7aeb78c6b1e17b))

## [1.4.76](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.75...v1.4.76) (2026-06-17)


### Bug Fixes

* push new version with Update ad patch fingerprints for MLB At Bat ([1aac811](https://github.com/ajstrick81/morphe-androidtv-patches/commit/1aac8110d104ea6ef5dfbf56179efe2189b568be))
* Refactor AtBatPatch for clarity and structure ([fbcd024](https://github.com/ajstrick81/morphe-androidtv-patches/commit/fbcd024b288acb7343fdf5b4ee499f9d6b42db22))

## [1.4.75](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.74...v1.4.75) (2026-06-16)


### Bug Fixes

* Refactor Fingerprints.kt for ad event suppression ([eec1fd6](https://github.com/ajstrick81/morphe-androidtv-patches/commit/eec1fd6082f207602ce496818d70bd24aceb4809))

## [1.4.74](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.73...v1.4.74) (2026-06-16)


### Bug Fixes

* Update FreewheelModuleSkipFingerprint definition ([5e0306e](https://github.com/ajstrick81/morphe-androidtv-patches/commit/5e0306e0a6f86d27da137d59bee17de66a585cbe))

## [1.4.73](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.72...v1.4.73) (2026-06-16)


### Bug Fixes

* Change compatibility from ATBAT_TV to MLB_TV ([6eb87fb](https://github.com/ajstrick81/morphe-androidtv-patches/commit/6eb87fb399625fc96ab9828a0ed5228e83ddea5f))
* Modify AtBatPatch for MLB At Bat compatibility ([7638422](https://github.com/ajstrick81/morphe-androidtv-patches/commit/7638422dbbcedd9de3e4aea9b6925edf8a5afd83))

## [1.4.72](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.71...v1.4.72) (2026-06-16)


### Bug Fixes

* push update for addition of MLB.tv to repo ([af7d735](https://github.com/ajstrick81/morphe-androidtv-patches/commit/af7d735729e017aeb2328c4eda836f1230b60847))
* Rename patch from 'MLB At Bat' to 'MLB.tv' ([07af607](https://github.com/ajstrick81/morphe-androidtv-patches/commit/07af607dea512607de8210118b1af78d319c06c8))

## [1.4.71](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.70...v1.4.71) (2026-06-15)


### Bug Fixes

* Fix formatting of method reference in ParamountPatch.kt ([9d26013](https://github.com/ajstrick81/morphe-androidtv-patches/commit/9d2601368c34d6dea1528195c36ed61ae79ac886))

## [1.4.70](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.69...v1.4.70) (2026-06-15)


### Bug Fixes

* Refactor ParamountPatch for ad suppression mechanism ([7f8f06f](https://github.com/ajstrick81/morphe-androidtv-patches/commit/7f8f06fa5befef65dfde245e56e67ab1f1de06fb))
* Refactor VOD SSAI and pause ads fingerprint strategies ([cbb0117](https://github.com/ajstrick81/morphe-androidtv-patches/commit/cbb0117efda945e60b3fa78dcc11277f3f26ae69))
* Update ParamountPatch to suppress VOD and pause ads ([2bd6988](https://github.com/ajstrick81/morphe-androidtv-patches/commit/2bd698808bbfde794ca2adb347849b85543d86ff))

## [1.4.69](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.68...v1.4.69) (2026-06-14)


### Bug Fixes

* new version push by adding target version to PARAMOUNT_TV compatibility ([ac43dd1](https://github.com/ajstrick81/morphe-androidtv-patches/commit/ac43dd1dbb4c92abc96f717beb5c1fc8c944dd1f))

## [1.4.68](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.67...v1.4.68) (2026-06-14)


### Bug Fixes

* push new version with Update Disney+ compatibility targets ([ef74d13](https://github.com/ajstrick81/morphe-androidtv-patches/commit/ef74d136763c00195ca18390dad3fbff64eaa147))

## [1.4.67](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.66...v1.4.67) (2026-06-13)


### Bug Fixes

* new version and Enhance AdBlockInterceptor with randomized responses ([007e8c6](https://github.com/ajstrick81/morphe-androidtv-patches/commit/007e8c6559bc40c538e66de6bb18d7d8b146055b))

## [1.4.66](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.65...v1.4.66) (2026-06-13)


### Bug Fixes

* push new version with refactor comments and logic in PeacockWebViewHelper ([2fa200f](https://github.com/ajstrick81/morphe-androidtv-patches/commit/2fa200ff70dbf1d62f22659c1e3bfbdbc6db5ce4))

## [1.4.65](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.64...v1.4.65) (2026-06-13)


### Bug Fixes

* push version with update Fingerprints.kt for ad playback state handling ([055ad5f](https://github.com/ajstrick81/morphe-androidtv-patches/commit/055ad5fc7ce6ee8399f937943267f8bec3c8dc95))

## [1.4.64](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.63...v1.4.64) (2026-06-13)


### Bug Fixes

* push new version and Update instruction index in SkipAdsPatch.kt ([056ade6](https://github.com/ajstrick81/morphe-androidtv-patches/commit/056ade686ff71319bcfbabf51c76b05128683c43))

## [1.4.63](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.62...v1.4.63) (2026-06-13)


### Bug Fixes

* Add client cert request handling and improve comments ([c60a808](https://github.com/ajstrick81/morphe-androidtv-patches/commit/c60a808c522d1df1e1a761cd7bc0331a0ab572bd))

## [1.4.62](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.61...v1.4.62) (2026-06-13)


### Bug Fixes

* new version Refactor WebViewClient interception logic ([1ae2b03](https://github.com/ajstrick81/morphe-androidtv-patches/commit/1ae2b0368f639dacb3f7e5a8a7f82a800ed2dae1))

## [1.4.61](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.60...v1.4.61) (2026-06-13)


### Bug Fixes

* push new version Refactor PeacockWebViewHelper for clarity and safety ([060680f](https://github.com/ajstrick81/morphe-androidtv-patches/commit/060680fac74817f05a2d331b622b1ff544fc7402))

## [1.4.60](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.59...v1.4.60) (2026-06-13)


### Bug Fixes

* update and Improve comments in Fingerprints.kt for XTVWebView ([bc4d815](https://github.com/ajstrick81/morphe-androidtv-patches/commit/bc4d81536923685d624639ead1772d96d6231ea5))

## [1.4.59](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.58...v1.4.59) (2026-06-12)


### Bug Fixes

* new version and Enhance skipAdsPatch with WebView interception ([9fab827](https://github.com/ajstrick81/morphe-androidtv-patches/commit/9fab8275292d6f0c24b38530f12bd94efcc6cc85))
* push new version with refactor SkipAdsPatch for clarity and efficiency ([a3ab178](https://github.com/ajstrick81/morphe-androidtv-patches/commit/a3ab178ffde8d9ba8af3bc511b9ce3d27102cef5))

## [1.4.58](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.57...v1.4.58) (2026-06-12)


### Bug Fixes

* Refactor ad injection method to avoid VerifyErrors ([7598779](https://github.com/ajstrick81/morphe-androidtv-patches/commit/75987792f27a8049af5e1cea353faee062a601fb))

## [1.4.57](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.56...v1.4.57) (2026-06-12)


### Bug Fixes

* Refactor AdBlockInterceptor injection method ([e5f4c43](https://github.com/ajstrick81/morphe-androidtv-patches/commit/e5f4c4382b6b70b00f78fc8bd5b4249918b6e73d))

## [1.4.56](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.55...v1.4.56) (2026-06-11)


### Bug Fixes

* push new version update Fingerprints.kt ([15fabeb](https://github.com/ajstrick81/morphe-androidtv-patches/commit/15fabebf35794d7fa356e93286aaba9ee2ad7cdd))

## [1.4.55](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.54...v1.4.55) (2026-06-11)


### Bug Fixes

* Delete obsolete ProGuard rules for extensions ([40f15c6](https://github.com/ajstrick81/morphe-androidtv-patches/commit/40f15c6579a2007d1967e67554a66ebe487c3939))

## [1.4.54](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.53...v1.4.54) (2026-06-10)


### Bug Fixes

* Refactor supplementaryAdsPatch to remove interstitial ad hook ([89a5c12](https://github.com/ajstrick81/morphe-androidtv-patches/commit/89a5c125186db152ae7d30b5ab75d3475bdcc01a))

## [1.4.53](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.52...v1.4.53) (2026-06-10)


### Bug Fixes

* Add four new hooks for ad suppression and opt-out ([daafb60](https://github.com/ajstrick81/morphe-androidtv-patches/commit/daafb606f3f96bb73cd30d3c35c87cea75cdb2e4))

## [1.4.52](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.51...v1.4.52) (2026-06-09)


### Bug Fixes

* force new version with update Fingerprints.kt ([a9028f7](https://github.com/ajstrick81/morphe-androidtv-patches/commit/a9028f7f6b6919a28929b2c7adfc2aeb5084a581))

## [1.4.51](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.50...v1.4.51) (2026-06-09)


### Bug Fixes

* Refactor ad playback strategy for improved performance ([aafdbcd](https://github.com/ajstrick81/morphe-androidtv-patches/commit/aafdbcdd5bdbfa00bfadf071015d8b96bbef11d5))

## [1.4.50](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.49...v1.4.50) (2026-06-09)


### Bug Fixes

* new version and enhance GMB diagnostic logging with inline smali ([c0da5ec](https://github.com/ajstrick81/morphe-androidtv-patches/commit/c0da5ec07a68a8966fd571d8d62b1c858a98f6a7))

## [1.4.49](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.48...v1.4.49) (2026-06-08)


### Bug Fixes

* push updated and enhance comments for SkipAdsPatch functionality ([9dbad4a](https://github.com/ajstrick81/morphe-androidtv-patches/commit/9dbad4a8d3cb4552037e02784d4f5b0daad16243))

## [1.4.48](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.47...v1.4.48) (2026-06-08)


### Bug Fixes

* push new version with update Fingerprints.kt ([5ec69a7](https://github.com/ajstrick81/morphe-androidtv-patches/commit/5ec69a72b9f5df4014124047d5684cfae1b23f53))

## [1.4.47](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.46...v1.4.47) (2026-06-05)


### Bug Fixes

* Refactor ad playback state handling in SkipAdsPatch ([cb0b454](https://github.com/ajstrick81/morphe-androidtv-patches/commit/cb0b454f85b9cef1e8b8625f425320d8fc160686))

## [1.4.46](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.45...v1.4.46) (2026-06-05)


### Bug Fixes

* force new version update Fingerprints.kt ([a4747fb](https://github.com/ajstrick81/morphe-androidtv-patches/commit/a4747fb1e2a789ce72c6c58c15bdbfdba0e25308))

## [1.4.45](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.44...v1.4.45) (2026-06-04)


### Bug Fixes

* force new version update Fingerprints.kt ([201af65](https://github.com/ajstrick81/morphe-androidtv-patches/commit/201af65cb7aa8251c5da327e503fbf7138ae23a5))

## [1.4.44](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.43...v1.4.44) (2026-06-04)


### Bug Fixes

* push new version update Fingerprints.kt ([79c0575](https://github.com/ajstrick81/morphe-androidtv-patches/commit/79c057584f444f6a682241685bd90230f4d22f0f))

## [1.4.43](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.42...v1.4.43) (2026-06-04)


### Bug Fixes

* Update Fingerprints: Remove and add SDK fingerprints ([36f3bd5](https://github.com/ajstrick81/morphe-androidtv-patches/commit/36f3bd5e023e55a49473ad5f92451f4f4a076597))

## [1.4.42](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.41...v1.4.42) (2026-06-03)


### Bug Fixes

* force version update via SkipAdsPatch.kt ([4c5bf0d](https://github.com/ajstrick81/morphe-androidtv-patches/commit/4c5bf0d82e8389e21e0d6ac4411e30d1cf6662b5))

## [1.4.41](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.40...v1.4.41) (2026-06-03)


### Bug Fixes

* Refactor Fingerprints.kt with updated patch strategies ([4c45180](https://github.com/ajstrick81/morphe-androidtv-patches/commit/4c4518027d6d711475e22d932e8c777ef3bece45))

## [1.4.40](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.39...v1.4.40) (2026-06-02)


### Bug Fixes

* push version update through Fingerprints.kt ([54336ae](https://github.com/ajstrick81/morphe-androidtv-patches/commit/54336aed6019be759d1664c5eea888a05c0af523))

## [1.4.39](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.38...v1.4.39) (2026-06-02)


### Bug Fixes

* Refactor SkipAdsPatch to simplify ad handling ([7eaede7](https://github.com/ajstrick81/morphe-androidtv-patches/commit/7eaede7510ae55a56a1c58d77d16e41c32bd0dec))

## [1.4.38](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.37...v1.4.38) (2026-06-02)


### Bug Fixes

* push new version through update of SkipAdsPatch.kt ([f58ac6b](https://github.com/ajstrick81/morphe-androidtv-patches/commit/f58ac6be345a9589f05f43d127d427eca512edb9))
* pushing new version by updating Fingerprints.kt ([ab4ba28](https://github.com/ajstrick81/morphe-androidtv-patches/commit/ab4ba28d2266e2851aaa8f5cc41ca92cef79099e))
* Refactor Fingerprints.kt package and imports ([e39c4d6](https://github.com/ajstrick81/morphe-androidtv-patches/commit/e39c4d626b6261c529a89cf6dfb78d1e7205279d))
* Rename package in Fingerprints.kt ([4f6bd18](https://github.com/ajstrick81/morphe-androidtv-patches/commit/4f6bd18da4714f42140a956d4c9a4458eb99409a))

## [1.4.37](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.36...v1.4.37) (2026-06-02)


### Bug Fixes

* Update comments and method name for ad pause patch ([1a06d95](https://github.com/ajstrick81/morphe-androidtv-patches/commit/1a06d95ee5b293e1e52b49d66f1abfc6565a0817))

## [1.4.36](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.35...v1.4.36) (2026-06-02)


### Bug Fixes

* Refine Paramount patch for ad handling ([cbe5e59](https://github.com/ajstrick81/morphe-androidtv-patches/commit/cbe5e5904f2dfcb1ebeb4f53bf065c35da7ebb2c))

## [1.4.35](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.34...v1.4.35) (2026-06-02)


### Bug Fixes

* Refactor DisneyPatch for clarity and updates ([b626bd1](https://github.com/ajstrick81/morphe-androidtv-patches/commit/b626bd175b1029a6dd1c0b433987c464b19167c1))

## [1.4.34](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.33...v1.4.34) (2026-06-01)


### Bug Fixes

* Change compatibility from PARAMOUNT_PLUS_TV to PARAMOUNT_TV ([96dc1a8](https://github.com/ajstrick81/morphe-androidtv-patches/commit/96dc1a873621549517c28df0b2cbcc7be501553d))
* Refactor PauseAdOverlayFingerprint custom logic ([70c4804](https://github.com/ajstrick81/morphe-androidtv-patches/commit/70c4804acd3c9785271dbb878df5e9a3899558b2))

## [1.4.33](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.32...v1.4.33) (2026-06-01)


### Bug Fixes

* Update Fingerprints.kt for Disney+ Android TV v26.8.0rc6 ([5140813](https://github.com/ajstrick81/morphe-androidtv-patches/commit/5140813ad11756cbd3134f92b243a5d3d08fbb7e))

## [1.4.32](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.31...v1.4.32) (2026-06-01)


### Bug Fixes

* Enhance Skip Ads patch with compatibility check ([2158994](https://github.com/ajstrick81/morphe-androidtv-patches/commit/215899498531185b6799ef8e6a465f183c68edb4))

## [1.4.31](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.30...v1.4.31) (2026-06-01)


### Bug Fixes

* Simplify SkipAdsPatch description and clean up code ([ab3e2bf](https://github.com/ajstrick81/morphe-androidtv-patches/commit/ab3e2bfa268071f85c4a1636a687a0b036f5a0b6))

## [1.4.30](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.29...v1.4.30) (2026-06-01)


### Bug Fixes

* Refactor Fingerprints.kt for Fox One integration ([23bdb4e](https://github.com/ajstrick81/morphe-androidtv-patches/commit/23bdb4eab64959710996db57ac23758424634d5f))
* Refactor imports in Fingerprints.kt ([07ae48c](https://github.com/ajstrick81/morphe-androidtv-patches/commit/07ae48c9b0a4e5d0dbc70d591ed99fcd71097e42))
* Rename patch from Fox One to Fox Sports ([ae60e8b](https://github.com/ajstrick81/morphe-androidtv-patches/commit/ae60e8bf98ed90cb54e322a0abdfa0f14fd28807))

## [1.4.29](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.28...v1.4.29) (2026-05-31)


### Bug Fixes

* Update comments and add new ad server checks ([a39195d](https://github.com/ajstrick81/morphe-androidtv-patches/commit/a39195d92bd3ea1810c8672a8642771f92aac4b5))

## [1.4.28](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.27...v1.4.28) (2026-05-31)


### Bug Fixes

* add Tubi ad domains block in SkipAdsPatch ([bca79ee](https://github.com/ajstrick81/morphe-androidtv-patches/commit/bca79ee15d39d7ffb64563b8d17ca9e009758247))

## [1.4.27](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.26...v1.4.27) (2026-05-30)


### Bug Fixes

* Add ad suppression hooks for Tubi video player ([13b66fa](https://github.com/ajstrick81/morphe-androidtv-patches/commit/13b66fa1a4b5d62e5e6ef9beb4a6257c4ae904e7))
* Implement comprehensive ad suppression mechanisms ([421f039](https://github.com/ajstrick81/morphe-androidtv-patches/commit/421f0391c5b6d4179f550560d0fea757e2a158b2))

## [1.4.26](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.25...v1.4.26) (2026-05-30)


### Bug Fixes

* Change default namespace for extensions ([d4b6334](https://github.com/ajstrick81/morphe-androidtv-patches/commit/d4b63344b26ecaf15cb7ba1903789ac33664afd0))

## [1.4.25](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.24...v1.4.25) (2026-05-30)


### Bug Fixes

* Fix check-cast for shouldBlock return type to stop app crash ([090bbc0](https://github.com/ajstrick81/morphe-androidtv-patches/commit/090bbc0a049a2d5ff9741ade374c69594a9342ca))

## [1.4.24](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.23...v1.4.24) (2026-05-30)


### Bug Fixes

* Update method invocation in SkipAdsPatch.kt to avoid app crash on startup ([8bd5c84](https://github.com/ajstrick81/morphe-androidtv-patches/commit/8bd5c84e2dc44a3cfdf36b2a828ec4bbda9cb04a))

## [1.4.23](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.22...v1.4.23) (2026-05-30)


### Bug Fixes

* Refactor comments in SkipAdsPatch.kt to avoid app crash ([0b1bd23](https://github.com/ajstrick81/morphe-androidtv-patches/commit/0b1bd2393740d099e0c4b2d793933dfca79f5706))

## [1.4.22](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.21...v1.4.22) (2026-05-30)


### Bug Fixes

* Implement ad request interception in TubiWebClient ([a7342b1](https://github.com/ajstrick81/morphe-androidtv-patches/commit/a7342b1e2784859010b669b2add0e69c13e651f8))

## [1.4.21](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.20...v1.4.21) (2026-05-30)


### Bug Fixes

* Add fingerprints for FoxIma stream requests ([d435003](https://github.com/ajstrick81/morphe-androidtv-patches/commit/d43500327ac868bb454defa76427b2d6acbed29f))

## [1.4.20](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.19...v1.4.20) (2026-05-29)


### Bug Fixes

* Update package name for HBO Max Android TV compatibility ([9e033ad](https://github.com/ajstrick81/morphe-androidtv-patches/commit/9e033adb2bf4312e00948dd2e704e08ab0687cd7))

## [1.4.19](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.18...v1.4.19) (2026-05-29)


### Bug Fixes

* Refactor Helio ad fingerprints and coroutine handling ([04c817e](https://github.com/ajstrick81/morphe-androidtv-patches/commit/04c817ea310e65b8cebb922a9b6a2962cc3ce1d9))

## [1.4.18](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.17...v1.4.18) (2026-05-29)


### Bug Fixes

* push Update SkipAdsPatch.kt to test Peacock ([903c74d](https://github.com/ajstrick81/morphe-androidtv-patches/commit/903c74d68a5462ecd14cd98c4dc10958fd27dfc4))

## [1.4.17](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.16...v1.4.17) (2026-05-28)


### Bug Fixes

* push version update through Fingerprints.kt ([b20232e](https://github.com/ajstrick81/morphe-androidtv-patches/commit/b20232e4f539ad026dc91e661b5567674443fb82))

## [1.4.16](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.15...v1.4.16) (2026-05-28)


### Bug Fixes

* add AppTarget for Disney and update HBO Max compatibility name for Android TV ([bacee4a](https://github.com/ajstrick81/morphe-androidtv-patches/commit/bacee4a39555170a79cecb391bd57e9d3ae3fc18))

## [1.4.15](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.14...v1.4.15) (2026-05-28)


### Bug Fixes

* rollback update Fingerprints.kt ([556efdd](https://github.com/ajstrick81/morphe-androidtv-patches/commit/556efdd3bb60043e2b434db97006fe22fe12c356))

## [1.4.14](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.13...v1.4.14) (2026-05-27)


### Bug Fixes

* Refactor comments for clarity in SkipAdsPatch.kt ([2114aa6](https://github.com/ajstrick81/morphe-androidtv-patches/commit/2114aa6b1307cbc99ccc1c5945b4d763ce4e6543))

## [1.4.13](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.12...v1.4.13) (2026-05-27)


### Bug Fixes

* Update SkipAdsPatch to use invoke-static/range ([a3ca8bf](https://github.com/ajstrick81/morphe-androidtv-patches/commit/a3ca8bfd260ce363617cdce103bc037542c2bba8))

## [1.4.12](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.11...v1.4.12) (2026-05-26)


### Bug Fixes

* Delete patches/src/main/kotlin/ajstrick81/morphe/patches/primevideo/misc/permissions directory ([65515b9](https://github.com/ajstrick81/morphe-androidtv-patches/commit/65515b92873265ae44faefc96e061c25b52fe33e))

## [1.4.11](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.10...v1.4.11) (2026-05-26)


### Bug Fixes

* Update SkipAdsPatch.kt ([b1abecd](https://github.com/ajstrick81/morphe-androidtv-patches/commit/b1abecdca6419abd6c32bc84baa4efed79fe7157))

## [1.4.10](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.9...v1.4.10) (2026-05-25)


### Bug Fixes

* Implement Nowtilus kill switch in HBOAdsPatch ([c95084b](https://github.com/ajstrick81/morphe-androidtv-patches/commit/c95084b2beea95de31216169dfe5362999b229de))

## [1.4.9](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.8...v1.4.9) (2026-05-25)


### Bug Fixes

* Refactor code for 'HBO Max - Disable Ads' ([f55ebc2](https://github.com/ajstrick81/morphe-androidtv-patches/commit/f55ebc29dd0f128d647b1a84c463a88aa5d42c3a))

## [1.4.8](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.7...v1.4.8) (2026-05-25)


### Bug Fixes

* Refactor Fingerprints for ad request and timeline builders ([506a49c](https://github.com/ajstrick81/morphe-androidtv-patches/commit/506a49cde31a23c1a284445edfe2888d17dd5ea6))

## [1.4.7](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.6...v1.4.7) (2026-05-25)


### Bug Fixes

* Modify BoltNonLinearAdsRequest fingerprint for JSON key ([fb28017](https://github.com/ajstrick81/morphe-androidtv-patches/commit/fb28017b792ed5b0278df849e58206578f0ee6d6))

## [1.4.6](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.5...v1.4.6) (2026-05-25)


### Bug Fixes

* Refactor HBOAdsPatch for clarity and efficiency ([2a47b14](https://github.com/ajstrick81/morphe-androidtv-patches/commit/2a47b14538e338e83f7e06edd8bada1afc44975f))

## [1.4.5](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.4...v1.4.5) (2026-05-25)


### Bug Fixes

* Fix typo in SSAI parsing failure string ([38c04a6](https://github.com/ajstrick81/morphe-androidtv-patches/commit/38c04a6962b3b275b23d9f0fe4419dc3f9fe8c09))

## [1.4.4](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.3...v1.4.4) (2026-05-25)


### Bug Fixes

* Update HBO Max package name to com.wbd.stream ([6b0c55d](https://github.com/ajstrick81/morphe-androidtv-patches/commit/6b0c55d26300ab774e3d65fd7598382011624e84))

## [1.4.3](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.2...v1.4.3) (2026-05-25)


### Bug Fixes

* Update HBO Max package name in AppCompatibilities ([b374810](https://github.com/ajstrick81/morphe-androidtv-patches/commit/b374810cb92206b34b8bb5725afe54e51711ce5a))

## [1.4.2](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.1...v1.4.2) (2026-05-25)


### Bug Fixes

* Update AppCompatibilities.kt to include HBOmax ([99bee43](https://github.com/ajstrick81/morphe-androidtv-patches/commit/99bee43fc262767c630cd293e5d286d4fe317b0d))

## [1.4.1](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.4.0...v1.4.1) (2026-05-24)


### Bug Fixes

* Update returnType in ImaSdkFactoryFingerprint ([ed7ed27](https://github.com/ajstrick81/morphe-androidtv-patches/commit/ed7ed2744626527d2755ca426d0555fdc37a8b41))

# [1.4.0](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.3.1...v1.4.0) (2026-05-24)


### Features

* restructure Paramount+ patch with separate Fingerprints file ([badd2c2](https://github.com/ajstrick81/morphe-androidtv-patches/commit/badd2c281981becff17d529d58961ec2caa10d59))

## [1.3.1](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.3.0...v1.3.1) (2026-05-19)


### Bug Fixes

* return null from ImaSdkFactory.zzb to prevent AdsLoader creation ([71d02aa](https://github.com/ajstrick81/morphe-androidtv-patches/commit/71d02aab549dd01bc00176b3af0e9f32f8f43c50))

# [1.3.0](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.2.0...v1.3.0) (2026-05-19)


### Bug Fixes

* pin Paramount+ to v16.8.0 ([250eae2](https://github.com/ajstrick81/morphe-androidtv-patches/commit/250eae26bf5da15fa8b19d69306e5fe4b417c953))


### Features

* update app names for Paramount and Disney Plus ([a6450cf](https://github.com/ajstrick81/morphe-androidtv-patches/commit/a6450cfdfc489ef645afd69922ef770210150669))

# [1.2.0](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.1.3...v1.2.0) (2026-05-19)


### Features

* initial Paramount+ and Disney+ ad blocking release ([9ae71e6](https://github.com/ajstrick81/morphe-androidtv-patches/commit/9ae71e63c3060b2b495e49591d3d7c81efe01d18))

## [1.1.3](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.1.2...v1.1.3) (2026-05-19)


### Bug Fixes

* Add Gradle credentials setup in release workflow via Morphe tutorial ([cdc695e](https://github.com/ajstrick81/morphe-androidtv-patches/commit/cdc695e0b72e1e24d16d15769553b2ec023376a9))
* Clarify patch compatibility for Paramount versions ([6dfb89d](https://github.com/ajstrick81/morphe-androidtv-patches/commit/6dfb89d875afbdbec3a1392d16544d7ed42cc336))
* Make gradlew executable in release workflow ([e0bc1ff](https://github.com/ajstrick81/morphe-androidtv-patches/commit/e0bc1fff7a0b5985b69436fb15ced00b07b9909e))
* update Gradle wrapper to 9.4.1 ([a0c8799](https://github.com/ajstrick81/morphe-androidtv-patches/commit/a0c8799e4dbbb0bbd69fb8221a2f572d63fa1205))
* Update release workflow permissions and build steps ([bdc2476](https://github.com/ajstrick81/morphe-androidtv-patches/commit/bdc24760520ed7cf6b1d0db742f8910dda9372a2))

## [1.1.2](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.1.1...v1.1.2) (2026-05-18)


### Bug Fixes

* trigger release with Gradle credentials fix ([26459d4](https://github.com/ajstrick81/morphe-androidtv-patches/commit/26459d44339e1dc5e541c1afb1981decea348639))


Release v1.1.2

## [1.1.1](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.1.0...v1.1.1) (2026-05-18)


### Bug Fixes

* resolve Gradle credentials for publish step ([0a3e95b](https://github.com/ajstrick81/morphe-androidtv-patches/commit/0a3e95b0b595453727c6496c7a08a16e440e3494))


Release v1.1.1

# [1.1.0](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.0.2...v1.1.0) (2026-05-18)


### Features

* initial Paramount+ and Disney+ ad blocking patches ([dca286e](https://github.com/ajstrick81/morphe-androidtv-patches/commit/dca286eea91711f896674136521435cf74bb208d))


Release v1.1.0

## [1.0.2](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.0.1...v1.0.2) (2026-05-18)


### Bug Fixes

* trigger initial patch release ([b9ec362](https://github.com/ajstrick81/morphe-androidtv-patches/commit/b9ec3621e4ff462492d2d6f081a9370239f712c5))


Release v1.0.2

## [1.0.1](https://github.com/ajstrick81/morphe-androidtv-patches/compare/v1.0.0...v1.0.1) (2026-05-18)


### Bug Fixes

* update package-lock.json and bump Node to 22 ([899748f](https://github.com/ajstrick81/morphe-androidtv-patches/commit/899748f0a2924f27714201ce18f4a6412a2c831a))


Release v1.0.1
