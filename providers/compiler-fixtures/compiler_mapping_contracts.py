"""Versioned SOURCE-test DATA comparisons; never a runtime registry, process or evidence loader."""
from pathlib import Path, PurePosixPath
import hashlib
import json

MAP_REL = 'modules/provider-compiler/src/main/resources/compiler-rule-map.json'
CORE_REL = 'data/rules-core.json'
SELECTION_REL = 'providers/coverage/current-inputs.json'
DATA_REL = 'providers/compiler-fixtures/mapping-contracts/legacy443-data'
CURRENT_CONTRACT = 'kotlin-compiler-2.4.21-common445-u2-v1'
LEGACY_CONTRACT = 'kotlin-compiler-2.4.21-common443-legacy-v1'
CURRENT_MAP_SHA256 = 'f8566bded002ebeb09a047924036770dbefaa687950355cb08d7174e5f82a2de'
CURRENT_CORE_SHA256 = '28860cb987390ff1f604488aba023fab08b614f497155b71a9760f326c009694'
LEGACY_MAP_SHA256 = 'f05c525e450c35cd7a7f701361fdb6467e00e7afd82a1c7cb4cfb60d728841e5'
PRE_ACTIVATION_MAP_SHA256 = '0db373ac11f29b911317d12a51426ce690c50c3a02e327d086879541df7cc29c'
NEW_IDS = ('ktc.compiler.common.error_suppression', 'ktc.compiler.common.inapplicable_operator_modifier')
REFERENCE_CHANGES = {
    'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/CompilerRuleRegistry.kt': (
        '4acda521ed44c2c30232c2ebd47ef4ed7ecde85e589736d48a98937a0191e667',
        '02cae63a16b07c5682a32772e7867648ad4cdf01dc2f958024da620c7f1418e8'),
    'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/KotlinJvmCompilerProvider.kt': (
        '3b631237d98703f077ac2af665c782af4213f69fdf203bd4d426f55a70274e63',
        '8bfcb578f6a23203619fdc5f4257eb46580b39b65c9ae553b50e3f0a9d7dcb16'),
    'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/CompilerDiagnosticParser.kt': (
        'db0048e7083bfc8d1565d0505d1b3c00ad574e6e50ef2dc39797a947912e3f3e',
        '48fa09fc3f57241160b1d6d964c4a41f01edead05354afbcc5f2cde9f62a372d'),
}
# Authored observation successor SOURCE only, not execution/qualification evidence.
# Keep this exact23 implementation closure and these five selected payloads separate
# from the unchanged historical/current389 name roster and historical current-inputs.
CURRENT_IMPLEMENTATION_SOURCE_PINS = {'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/CompilerCallObservation.kt': 'c825651b4f1e32f66539017c3a11e573507a27c7d99eefd73c896998c9ef7273',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/CompilerDiagnosticParser.kt': '48fa09fc3f57241160b1d6d964c4a41f01edead05354afbcc5f2cde9f62a372d',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/CompilerFlags.kt': '750ca4544a2b801bb6020838410ec92e7278a1a6a2ca4f10e58cd17b10819271',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/CompilerPhaseTimings.kt': '7716e2e472debfb4c52bae6b9c8a28856d1e04b8ad8ce9304f479139fc0c89a9',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/CompilerRuleRegistry.kt': '02cae63a16b07c5682a32772e7867648ad4cdf01dc2f958024da620c7f1418e8',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/CompilerSnapshot.kt': 'd089b942352d99fa4f0920338669d0fbc35bbc4112fc7d032f7e8ff0684015b7',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/CompilerVerification.kt': 'a7930f91d8c3031bd39b5fab47c826db29ccd846189912b5e5f2d51d88196bd9',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/JvmCompilerModelOrigin.kt': 'ab6bc829611cbe18b5f87149acededac2f4b1e5e3506c6fe09d874886ff6272f',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/KotlinJvmCompilerProvider.kt': '8bfcb578f6a23203619fdc5f4257eb46580b39b65c9ae553b50e3f0a9d7dcb16',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/KotlinJvmInstallationView.kt': '9d9e12dfe7315a9e672d8d2d8a49979e044945c31cd73653155f320aafbf7738',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/KotlinJvmModelBinding.kt': '193952ea5d7a196490d0216747da3ced5ed4dac5376275943dfcbf17742b9f64',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/KotlinJvmRuntimeImage.kt': 'b2f63b10401c42e94376aeac4998933d743d6b60346442677c5490a07a626aea',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/live/CompilerApplicationAuthority.kt': 'aa06bcf556e133db854f121c44d3a405059dfff8fb26db9ce9c0ef3f3d183671',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/live/CompilerApplicationLifecycle.kt': 'f1d713019fbaaa78258dd0802b7c2c1b007e9bcc36be0df6a7d89c49fcd5acc7',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/live/KotlinJvmCompilerApplication.kt': '9e7a949b4d9a0b22ca3b95c3467cb8d29410c4c50dc33a76540dcb79b0059c18',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/live/KotlinJvmLiveCompilerProvider.kt': 'b09bfde3969cd9ab5c6386c472bad2bdf2472dfd752b9253af9261ed8eb9baca',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/live/model/JvmProducedDeclaredArchive.kt': 'c8160489733faa22bd3d08e380cdb2ecb70abdee945a1f40c0f4a638ff914b05',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/live/model/JvmProducedOutputPreview.kt': '71bbd55dc444c8491dc61b1a99edd67812e2cd20b9e96d9ce92f60cf0360312b',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/live/model/KotlinJvmModelCompilerApplication.kt': '94414a98329a39b27897039b91a04c5687a2182529add8bab62dbac471ad3988',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/live/model/KotlinJvmModelLiveCompilerProvider.kt': '2af16909f0936df89b426147e3fbf20d47f6ed5acfdc877eea2eccc7b9e5f6be',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/live/model/ModelApplicationAuthority.kt': 'bdf38f3b76364708d69281b1c052126c0169ef93fec73ffe1ae072218f241c72',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/live/model/ModelLiveInputs.kt': 'abf03f03e6d61b8a6ee3568a1b27ca753d1917b514d8d152bf55ab4cd9bda1e0',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/live/model/ModelMaterialization.kt': '28281cdc4e1af7c54413c53441672b454d3bb7f9469c612db1267ac61739e6f3'}
OBSERVATION_SOURCE_PINS = {'modules/cli/src/main/kotlin/dev/ktchecker/cli/Workbench.kt': {'bytes': 42659,
                                                                'sha256': 'f37d4784f760379b386e195c1f1d3115201578caf707b04e43b5da65829f16ab'},
 'modules/cli/src/test/kotlin/dev/ktchecker/cli/WorkbenchCompilerObservationTest.kt': {'bytes': 11386,
                                                                                       'sha256': 'e163f8ad7bfd385c142a13faa65dc0a923d1c6969e3704d7b8b88602829058e9'},
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/CompilerCallObservation.kt': {'bytes': 19586,
                                                                                                 'sha256': 'c825651b4f1e32f66539017c3a11e573507a27c7d99eefd73c896998c9ef7273'},
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/KotlinJvmCompilerProvider.kt': {'bytes': 29802,
                                                                                                   'sha256': '8bfcb578f6a23203619fdc5f4257eb46580b39b65c9ae553b50e3f0a9d7dcb16'},
 'modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerCallObservationTest.kt': {'bytes': 40044,
                                                                                                     'sha256': '2fbcff9e7b85ff94f6d946ee25134bf8da270a600768814131598fb63632b3cd'}}

# Exact parser ambiguity successor SOURCE plus its new dedicated test; neither
# extends current389 or historical evidence selectors. The facade5 set is unchanged.
PARSER_AMBIGUITY_SOURCE_PINS = {'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/CompilerDiagnosticParser.kt': {'bytes': 17032,
                                                                                                  'sha256': '48fa09fc3f57241160b1d6d964c4a41f01edead05354afbcc5f2cde9f62a372d'},
 'modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerHeaderSourceAmbiguityTest.kt': {'bytes': 31290,
                                                                                                           'sha256': 'e2fe6db69b6098f03cd0ef0c4acc10d3f7557aea38769859fa829ebaf6d2f51a'}}

LEGACY_DATA_PINS = {'data/rules-core.json': {'file': 'rules-core.json.txt', 'bytes': 4989971, 'sha256': 'e49d7317951077d55a68df30387915c263f21dd3972caecf58f47138d885e014'}, 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/CompilerRuleRegistry.kt': {'file': 'CompilerRuleRegistry.kt.txt', 'sha256': '4acda521ed44c2c30232c2ebd47ef4ed7ecde85e589736d48a98937a0191e667', 'bytes': 3893}, 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/KotlinJvmCompilerProvider.kt': {'file': 'KotlinJvmCompilerProvider.kt.txt', 'sha256': '3b631237d98703f077ac2af665c782af4213f69fdf203bd4d426f55a70274e63', 'bytes': 28624}, 'modules/provider-compiler/src/main/resources/compiler-rule-map.json': {'file': 'compiler-rule-map.json.txt', 'sha256': 'f05c525e450c35cd7a7f701361fdb6467e00e7afd82a1c7cb4cfb60d728841e5', 'bytes': 2609377}, 'modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerCheckerActivationTest.kt': {'file': 'CompilerCheckerActivationTest.kt.txt', 'sha256': 'e445c44479e6ac15c8aa17e79167ed557f1240243934564e66eaf0eb03489da6', 'bytes': 8364}, 'modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerFixtureDiagnosticBreadthTest.kt': {'file': 'CompilerFixtureDiagnosticBreadthTest.kt.txt', 'sha256': 'e5c67dfd4dd109f75d557052f039da6696577d277b32c8f44c469683c534c603', 'bytes': 47236}, 'modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerFixtureNullabilityTest.kt': {'file': 'CompilerFixtureNullabilityTest.kt.txt', 'sha256': '786b9cb14b8c5ea0462d82244a40a9c7dd133d13a25cf12e68a70113532bf966', 'bytes': 38728}, 'modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerFixtureRunner.kt': {'file': 'CompilerFixtureRunner.kt.txt', 'sha256': '9ad5e860a3b7fe303bc28e9c5aedef51d6918909abab4c04888ef93c49935046', 'bytes': 147604}, 'modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerProviderTest.kt': {'file': 'CompilerProviderTest.kt.txt', 'sha256': '39176ac8f5b79ce775386357878509e25a9541d2837e40a77902ed6c8b3fcc7f', 'bytes': 20254}, 'providers/compiler-fixtures/generate_rule_map.py': {'file': 'generate_rule_map.py.txt', 'sha256': '12836b3b2f8d2ed24727ff1c2b96c9a1180b7c2ac3be29fc1b8dbcf6cc22910c', 'bytes': 3586}, 'providers/compiler-fixtures/test_checker_activation.py': {'file': 'test_checker_activation.py.txt', 'sha256': 'cc9b0ee1f1fbfe8afd73c17816bb592e975921526bd16a6217c14ea40118c763', 'bytes': 20741}, 'providers/compiler-fixtures/test_diagnostic_breadth.py': {'file': 'test_diagnostic_breadth.py.txt', 'sha256': '93de1bdd142c939864a1a72b39ccbbfc4161663c18e8e85db71633c5e5dedbe2', 'bytes': 29472}, 'providers/compiler-fixtures/test_diagnostic_breadth_hookup.py': {'file': 'test_diagnostic_breadth_hookup.py.txt', 'sha256': '4b18f75ae9067b75e4177345a9aa3f33d2e62e34ba629be271511d5ce7c86c09', 'bytes': 123491}, 'providers/compiler-fixtures/test_nullability_protocols.py': {'file': 'test_nullability_protocols.py.txt', 'sha256': '796f6d39d32527185855de0b4647e4966e5e7d6631108a42aeab98ec99836b2f', 'bytes': 24837}, 'providers/compiler-fixtures/test_nullability_protocols_hookup.py': {'file': 'test_nullability_protocols_hookup.py.txt', 'sha256': '1a96f723677a80d94b2d2eb9ab10acd8856ce0cbecec19ef6e51b20cd57acbd0', 'bytes': 39418}, 'providers/compiler-fixtures/test_original42_selectors.py': {'file': 'test_original42_selectors.py.txt', 'sha256': '7103af7c1da9f92664e7f9cee7f1fca0e7ea5926248e4bef07b615c0106941b0', 'bytes': 12745}, 'providers/compiler-fixtures/test_type_boundaries_supplemental.py': {'file': 'test_type_boundaries_supplemental.py.txt', 'sha256': '6a357412027c4d34ac91fea36aeccdba3b08454b96d7287c86313a9506e910cb', 'bytes': 14995}, 'providers/coverage/current-inputs.json': {'file': 'current-inputs.json.txt', 'sha256': '08d88559f95f86557efaa9b7663c8b16e66991f3548d119ec71af1b756f4b7a3', 'bytes': 19723}}
# Only these explicitly unchanged bytes may join a historical SOURCE DATA assertion.
UNCHANGED_SOURCE_PINS = {'data/rules-ecosystem.json': '261fb5c1fadbed7b9ae3b7768cba19e9716ba5067c4932e671183d13513fd2ac',
 'data/rules-libraries.json': '3e671d030bb1734242c4f51c2de8723c11abb002b9c8a743176e7cc957592c9e',
 'data/rules-platform.json': '0bfffdd0749353cb5b9b41485e29456037a5c669e232c6915fa5ca91aa6127af',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/CompilerFlags.kt': '750ca4544a2b801bb6020838410ec92e7278a1a6a2ca4f10e58cd17b10819271',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/CompilerPhaseTimings.kt': '7716e2e472debfb4c52bae6b9c8a28856d1e04b8ad8ce9304f479139fc0c89a9',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/CompilerSnapshot.kt': 'd089b942352d99fa4f0920338669d0fbc35bbc4112fc7d032f7e8ff0684015b7',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/CompilerVerification.kt': 'a7930f91d8c3031bd39b5fab47c826db29ccd846189912b5e5f2d51d88196bd9',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/JvmCompilerModelOrigin.kt': 'ab6bc829611cbe18b5f87149acededac2f4b1e5e3506c6fe09d874886ff6272f',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/KotlinJvmInstallationView.kt': '9d9e12dfe7315a9e672d8d2d8a49979e044945c31cd73653155f320aafbf7738',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/KotlinJvmModelBinding.kt': '193952ea5d7a196490d0216747da3ced5ed4dac5376275943dfcbf17742b9f64',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/KotlinJvmRuntimeImage.kt': 'b2f63b10401c42e94376aeac4998933d743d6b60346442677c5490a07a626aea',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/live/CompilerApplicationAuthority.kt': 'aa06bcf556e133db854f121c44d3a405059dfff8fb26db9ce9c0ef3f3d183671',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/live/CompilerApplicationLifecycle.kt': 'f1d713019fbaaa78258dd0802b7c2c1b007e9bcc36be0df6a7d89c49fcd5acc7',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/live/KotlinJvmCompilerApplication.kt': '9e7a949b4d9a0b22ca3b95c3467cb8d29410c4c50dc33a76540dcb79b0059c18',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/live/KotlinJvmLiveCompilerProvider.kt': 'b09bfde3969cd9ab5c6386c472bad2bdf2472dfd752b9253af9261ed8eb9baca',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/live/model/JvmProducedDeclaredArchive.kt': 'c8160489733faa22bd3d08e380cdb2ecb70abdee945a1f40c0f4a638ff914b05',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/live/model/JvmProducedOutputPreview.kt': '71bbd55dc444c8491dc61b1a99edd67812e2cd20b9e96d9ce92f60cf0360312b',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/live/model/KotlinJvmModelCompilerApplication.kt': '94414a98329a39b27897039b91a04c5687a2182529add8bab62dbac471ad3988',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/live/model/KotlinJvmModelLiveCompilerProvider.kt': '2af16909f0936df89b426147e3fbf20d47f6ed5acfdc877eea2eccc7b9e5f6be',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/live/model/ModelApplicationAuthority.kt': 'bdf38f3b76364708d69281b1c052126c0169ef93fec73ffe1ae072218f241c72',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/live/model/ModelLiveInputs.kt': 'abf03f03e6d61b8a6ee3568a1b27ca753d1917b514d8d152bf55ab4cd9bda1e0',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/live/model/ModelMaterialization.kt': '28281cdc4e1af7c54413c53441672b454d3bb7f9469c612db1267ac61739e6f3',
 'modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerFixtureControls.kt': 'bde583248f23979fee1c78d78b77f976dad9a302bb2c8f3e33185e7067ff8be0',
 'modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerFixtureControlsTest.kt': 'c7ce230f4637046b7333c42022763642c3389faf3bd3dd1614a4931f5874f34e',
 'modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerFixtureEvidence.kt': '2452a1e1e4303a90366a06911fdedf4ad629fdff014756f45f763888a8ebbb46',
 'modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerFixtureNullability.kt': '7ba767b7fe660979f1b5cd6ec81bd7295eec34f0e15197a578e636b87cfb8352',
 'modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerFixtureProvenance.kt': '9b3602227e4275e514aa9cae2335de4d471cc657a783fb1a890a9569f236d72d',
 'modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerFixtureScratch.kt': '76cf95545d5023ada15a7ec8b8bd2e55bae8a5759f2bf5bb8908a00d4a59993e',
 'modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerFixtureSupplemental.kt': 'e6104020de52316072d2e3491ca20296b5381e971a073acda69fc8ce6edd1379',
 'modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerFixtureSupplementalTest.kt': 'd1bc76856da36be79a385f260214ddb75f3ee0060006971ba36a76c1a27c5955',
 'modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerFixtureTimingProbe.kt': '2f89dd7fe83eb2189c310d7a6f972b2f2a9f409e3c6d3c4cb7963d7077ef43cf',
 'modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerFixtureTimingProbeTest.kt': 'cdc3c99660e318e06d4a5f435057f6353d35a3cad5346dbd1780ffb69ea0eaa4',
 'providers/compiler-fixtures/authored-input-inventory.json': 'b8c6af0b0f1d68578ad9a209e900c83668fc25f67bfd7fbf272fc28720511bad',
 'providers/compiler-fixtures/cases/abstract_class_member_not_implemented/negative.kt': 'd6eeb32afa4c44f8dcb1fbfa17c3d7ff8e65f73555ad66d872c35cd0eafbae20',
 'providers/compiler-fixtures/cases/abstract_class_member_not_implemented/positive.kt': '79534a425ffc16ef5ca5e1cea258e33402a4cb8e9ac21d9082a8c9cdcc96eb1f',
 'providers/compiler-fixtures/cases/abstract_member_not_implemented/negative.kt': 'd2f5117a62a36d93a46f48e76edea42cd237fe152be92407cb54165ac9c7ce42',
 'providers/compiler-fixtures/cases/abstract_member_not_implemented/positive.kt': '35eee0d9cf896855e70941e30214a2b2d70aa679e05ba0a5fcb1793eb04e9827',
 'providers/compiler-fixtures/cases/argument_passed_twice/negative.kt': '37051bf8322e7c636d451de85296d640d79121bb4fb45f0bcfc24674d1b397cc',
 'providers/compiler-fixtures/cases/argument_passed_twice/positive.kt': 'ea61635a65c3ff235a0bd1e8f8e2e78d4aa9bc9a6c861ffcac1a83a6d0d80470',
 'providers/compiler-fixtures/cases/argument_type_mismatch/negative.kt': '84ddc70e56de0a4da5dbc3c603d8978a57c146d1acfaf98b6ee3a136c5dd4c71',
 'providers/compiler-fixtures/cases/argument_type_mismatch/positive.kt': 'e3ca48f35c28d63c5df1a72ce786fefd02da2cb052cd104f5859951f4a76b0f7',
 'providers/compiler-fixtures/cases/assignment_type_mismatch/negative.kt': '0e2a8e5c13bd454f0f33f0b54c3cee6f19180852e89fbc8d2c4dcc51f0d12399',
 'providers/compiler-fixtures/cases/assignment_type_mismatch/positive.kt': '67b5b7ba4da08a6349637de33f955c029067001675c138e4571280df3e03d048',
 'providers/compiler-fixtures/cases/cannot_check_for_erased/applicability.kt': 'eae215a94605de750be76cd9dc911368ab5ab798123768651272948de460f044',
 'providers/compiler-fixtures/cases/cannot_check_for_erased/boundary.kt': 'cd63f250360d9bec939380d308169887d7017f562699a95e49f95c455215a6b8',
 'providers/compiler-fixtures/cases/cannot_check_for_erased/negative.kt': '963d057e188f6159b9af202333d67b84af907ac2caafbec5974600f1202b42f9',
 'providers/compiler-fixtures/cases/cannot_check_for_erased/positive.kt': '281ccfd6975f676b8b394610865f5b3ebc3be01f0366bcadc1772058772c8289',
 'providers/compiler-fixtures/cases/cannot_check_for_erased/suppression.kt': 'afa0a7509ae539b0b6c4dccae4977f52ff2d893b8a8a848548c7d9bec9aac087',
 'providers/compiler-fixtures/cases/cannot_weaken_access_privilege/negative.kt': '1aa15b6a55ad7faa2f3e7adaf3cc744516a1b09eaa3e7923b28271f288a1c8f3',
 'providers/compiler-fixtures/cases/cannot_weaken_access_privilege/positive.kt': '23c8dee72795dc31e20f3ca7edb1496cad49185e61042e1777fbb3ce615cdd7e',
 'providers/compiler-fixtures/cases/cast_never_succeeds/negative.kt': 'eec391d9fcce29e433f582ce9fdfd14bdbafac4c0b9ed5ad4eb3a1ff719f3a68',
 'providers/compiler-fixtures/cases/cast_never_succeeds/positive.kt': '4f48a28b4a370f9b35415b8fe64cca67bbfafa5df56704e2b80d3ef5399c0138',
 'providers/compiler-fixtures/cases/component_function_missing/negative.kt': '1328932f99a481611eb34394c00fb01274bfc184e601bd8fd22d014bcef0aa37',
 'providers/compiler-fixtures/cases/component_function_missing/positive.kt': '8696da0c1430a6d02dab5b00d0b82c0e723ac2615b943f28c798f7bfda837520',
 'providers/compiler-fixtures/cases/concurrent_hash_map_contains_operator_error/negative.kt': '385f3821ddafad60bbbd647a59628ad937e8284c0ad6cb57788bc25e83fe3989',
 'providers/compiler-fixtures/cases/concurrent_hash_map_contains_operator_error/positive.kt': '7d58fcfdc28f70b87dca63843743a17075e9be3fbfba7fdd6ef100bba987c2ff',
 'providers/compiler-fixtures/cases/condition_type_mismatch/negative.kt': 'a82b7fb9d1d1d59522a5cbf6bb0a4c999ce68818c3135ec3cfa4a1e9a953cdb5',
 'providers/compiler-fixtures/cases/condition_type_mismatch/positive.kt': '0fbd2eb6a37ea1a1d597a2fbd33457b33fdcdde4a8b3e904b73aa1913e38e28f',
 'providers/compiler-fixtures/cases/conflicting_upper_bounds/applicability.kt': 'f832b9c433d988981fb9167b8e9dab57531064dd0cbd60959fbe88262f51f1cb',
 'providers/compiler-fixtures/cases/conflicting_upper_bounds/boundary.kt': '58a7486a1f7dbdd8a72e2ea8f75e41504458925e7622bf61bc7ef2cd878d823c',
 'providers/compiler-fixtures/cases/conflicting_upper_bounds/negative.kt': '5bfa4499f2d64af40be295aa7bf1699b5ce14be59e26502c22da7fbbce1fc864',
 'providers/compiler-fixtures/cases/conflicting_upper_bounds/positive.kt': 'fc4bdd21471be6068e6cad3687ca96a8a10224bbcde45d1540a2a7b2c1f1058a',
 'providers/compiler-fixtures/cases/conflicting_upper_bounds/suppression.kt': 'a34f0128ad63764a731737ca044ed6d828fac2412359b7616510276a5b321fd0',
 'providers/compiler-fixtures/cases/cyclic_constructor_delegation_call/negative.kt': '5da8d528ed80fbc81d1068e9fa8d291c7969bec397bfa607b4e1416f7090d9a1',
 'providers/compiler-fixtures/cases/cyclic_constructor_delegation_call/positive.kt': 'b80ebedadb56de59997f66505ef94d5950e2852f4131020d6db8bf607c2dfaf5',
 'providers/compiler-fixtures/cases/delegation_by_in_jvm_record/negative.kt': '413512bb4d09ea967dc23acf9d1d9b3230b3b5be921a88778a809534891c5bcb',
 'providers/compiler-fixtures/cases/delegation_by_in_jvm_record/positive.kt': '8d055b9371b8592af32094034dbdbd4dbb4ce055d80ca8d98a3399a934c00557',
 'providers/compiler-fixtures/cases/deprecation/negative.kt': 'bd72e7cab839f26de91a9265f6a325150e0c20b736f3d16dff8080791fad54b0',
 'providers/compiler-fixtures/cases/deprecation/positive.kt': 'a2e39d83bc4b241581b5bb77f2353bc21b516ee87bfb74c51592cbbee4bd5399',
 'providers/compiler-fixtures/cases/division_by_zero/negative.kt': '768029687bcb1f822397d08592dc6dddf337c2eb1dfa1b949a4297df99c3076a',
 'providers/compiler-fixtures/cases/division_by_zero/positive.kt': '620cbd24b74cc65e49b5cb62f8132e32a03025794dc096f9647e066e6cb1bafc',
 'providers/compiler-fixtures/cases/empty_range/negative.kt': '79d3f1591aa626f44afa8cc18691dcabe9480163fa49d4d34d993e5f26bd9d78',
 'providers/compiler-fixtures/cases/empty_range/positive.kt': '823eb81e16992276d5cebd6fe5dc19bb7b314158e1af9c75df71caefe9fe76bd',
 'providers/compiler-fixtures/cases/equality_not_applicable/negative.kt': '05d044d6e4b21dc5ae2fdd23d52a00fd5514e1cc4dd8b35422fa66e3ceb5f144',
 'providers/compiler-fixtures/cases/equality_not_applicable/positive.kt': '0f96270187449d9bdb591e341c4dac54b435854473bf3d4bbd9eee538453bb11',
 'providers/compiler-fixtures/cases/exposed_function_return_type/negative.kt': 'f593184550060456f13aad945e690e8b35a5d35690aafb64b3b3cb781415ce47',
 'providers/compiler-fixtures/cases/exposed_function_return_type/positive.kt': '8963fb30a263ae6a268ed19743d7d7c8706781eebc15b62c1c381f963bd22d1e',
 'providers/compiler-fixtures/cases/exposed_parameter_type/negative.kt': 'ec5fb5304bcee052dbfe5ac7e0d6dc7ff2350dcd1579e1fce61983d245b003c2',
 'providers/compiler-fixtures/cases/exposed_parameter_type/positive.kt': 'c10128a902801f7cc84d909d2c909995a705e8535dd744db6cf900838e2f2ced',
 'providers/compiler-fixtures/cases/exposed_property_type/negative.kt': '234668fba3b2b92e655c80764fc400b9237d372513c9296f6068744f11f28472',
 'providers/compiler-fixtures/cases/exposed_property_type/positive.kt': '2ff0ba2456a384e068b2f7dd379bb985d6dd0ba34e12bf8d5a02187bc270a356',
 'providers/compiler-fixtures/cases/exposed_receiver_type/negative.kt': 'b7151bfcf975d22b312211a4c58c5d0cd55113844acb777fbdc5ca03e8a89aca',
 'providers/compiler-fixtures/cases/exposed_receiver_type/positive.kt': 'aeac5a238ebd076d5b07fb962f0550fd7864f2c8b48cd39b2852f6ce574b4bcb',
 'providers/compiler-fixtures/cases/exposed_super_class/negative.kt': '59f0b8b568adbe86a2ce7e4fa966622115ba13903bf81a3d6f85a41cd767aeb7',
 'providers/compiler-fixtures/cases/exposed_super_class/positive.kt': '11c39788ac5ed155db3d0af7be41a0c1bc4d0dc342b1a0e3aee6ce07b766055c',
 'providers/compiler-fixtures/cases/exposed_type_parameter_bound/negative.kt': 'e50250354473d1661b555e294911d4625b20e7494a80af6b2e83d3081f21f26f',
 'providers/compiler-fixtures/cases/exposed_type_parameter_bound/positive.kt': '23836bc80da256eb7052edeccd753d6fe4b318de407b81bbe7ee46e96099a15a',
 'providers/compiler-fixtures/cases/exposed_typealias_expanded_type/negative.kt': '7938eb0475bd145e09940a7a7782065b6df6ccf4675880407090ebd3678900d8',
 'providers/compiler-fixtures/cases/exposed_typealias_expanded_type/positive.kt': '235206623fafe122f87c84e01546df23b1a30e79bd36e79904fcf285a0a10d81',
 'providers/compiler-fixtures/cases/external_declaration_cannot_have_body/negative.kt': '909b551935117bb94e0432fca13903d5bb254f2775cc9c70f4bcf108cd6f5650',
 'providers/compiler-fixtures/cases/external_declaration_cannot_have_body/positive.kt': '8b64781a0667b325d7e715efdb3845e4210ff8bd15e974bc65b6b3c02c441669',
 'providers/compiler-fixtures/cases/external_declaration_in_interface/negative.kt': '8f23ebd059999667971de15b7facd71b980b8110acd6a8f4d9fcb06f1a5f5ff8',
 'providers/compiler-fixtures/cases/external_declaration_in_interface/positive.kt': '0d71bafa380fd1455f460969d23f0497d3de364e9e9f3044a6ac1b8f3e2512e5',
 'providers/compiler-fixtures/cases/field_in_jvm_record/negative.kt': 'decca913e3c2f1f3b4ac213fc74106be3df3a202e264a7e2d55e1780200600eb',
 'providers/compiler-fixtures/cases/field_in_jvm_record/positive.kt': '66b92c8378a9df8fd02153d7e69991736b2925be8debdbf656baaaacc1fc5a22',
 'providers/compiler-fixtures/cases/illegal_jvm_name/negative.kt': '37062636e6ecaf47a99e6a784923e4935008c2cbaa52fa1cb29b0b42bb1f0d56',
 'providers/compiler-fixtures/cases/illegal_jvm_name/positive.kt': '8f17b34b8bb1662a9cc7ab76bc381ab1d703421556b3a25d1805a9801a39b04b',
 'providers/compiler-fixtures/cases/inapplicable_jvm_field/negative.kt': 'f5fe1ea129f8033c79feb7440f41bf11d0b6ce8b64be34615b58932f3cf53e0d',
 'providers/compiler-fixtures/cases/inapplicable_jvm_field/positive.kt': 'f2da19a17c53a85c2ae3bbfe65d0d6e3b8322a344e698f7ea9cdf99e807d442f',
 'providers/compiler-fixtures/cases/inapplicable_jvm_name/negative.kt': 'b530c9e63d72d110f7c26a903d686f2da3974a2f883e8871496f2456a07fd4e3',
 'providers/compiler-fixtures/cases/inapplicable_jvm_name/positive.kt': '993e7d525e6060938c660939d1f337ba71cbd47983517dee2a18f41062f206ca',
 'providers/compiler-fixtures/cases/initializer_type_mismatch/negative.kt': '08997eea65a80881d420c90bf9d806bf242475791103c6e1fbaf1d0885533c44',
 'providers/compiler-fixtures/cases/initializer_type_mismatch/positive.kt': '09b2517fe550b84a8e18b33f99941e866fd0e76bd7bf757ea319fda4cca4f155',
 'providers/compiler-fixtures/cases/inline_property_with_backing_field/negative.kt': '316ed16e8c301b5d09cf35dbf0c2522112fe29a9eefc87f0a5c8315d5be7068a',
 'providers/compiler-fixtures/cases/inline_property_with_backing_field/positive.kt': '50efe6c65275e38567692c9397648730616c1bf397f224f44400fc3a3bf85570',
 'providers/compiler-fixtures/cases/int_literal_out_of_range/applicability.kt': '6e338567d56a6a600e208ebca03903c1854ca6dbba5d1c2e3ebe4f2f176e346c',
 'providers/compiler-fixtures/cases/int_literal_out_of_range/boundary.kt': '5317b90e45898a253afe0d5a9f58d6862e8264da8ce4dd033732a1a92f4a50ed',
 'providers/compiler-fixtures/cases/int_literal_out_of_range/negative.kt': '0652fc45d72342b7d22de60ec4f14d42f65f807556a3de42f00b9a87b6361f75',
 'providers/compiler-fixtures/cases/int_literal_out_of_range/positive.kt': 'efe8e9a9f74b50cc5dd7645659b7eed8e3928311bd350a4fc2f9d6f8e438a9c0',
 'providers/compiler-fixtures/cases/int_literal_out_of_range/suppression.kt': 'c8a323d1910f22dbd1d00b3598a6f8da2f47fe9adca9cb71de82e7429eb7dce7',
 'providers/compiler-fixtures/cases/invisible_reference/negative.kt': 'f9ffd0c5efcc6660dee7d888ce26ad0f421191509397c37c0fcae2761dd74080',
 'providers/compiler-fixtures/cases/invisible_reference/positive.kt': 'c2d57bbf3e09f626c1028030b1de7df442d09b33eb035309ebcecc6a3fa52aee',
 'providers/compiler-fixtures/cases/invisible_setter/negative.kt': 'dd06f082ca1425851359d26defa7a0d4e53b7c36744217bf0a0116165bc48cf3',
 'providers/compiler-fixtures/cases/invisible_setter/positive.kt': '1473d75d9bf2f859a5633c90e584984a00eec3b034c50fa3136f9a7a4e9711f1',
 'providers/compiler-fixtures/cases/iterator_missing/negative.kt': '642af9dc71fa4658c8b59a8b50a4e0dcdd10e4f6d7da682a789764ffb0fba3aa',
 'providers/compiler-fixtures/cases/iterator_missing/positive.kt': '5e663fba752b63fe14a602bc5daac3570c51848fbcec080f9dc44ee3e03bc846',
 'providers/compiler-fixtures/cases/jvm_inline_without_value_class/negative.kt': '25a249a86e91aba07c9b9a69f6441e2eaa12207d1aa68585d464c784d4364f94',
 'providers/compiler-fixtures/cases/jvm_inline_without_value_class/positive.kt': '95a5b2916882ac920002f5cc6e5e813cfe7cccd4513a385b21deccc9fc4779ce',
 'providers/compiler-fixtures/cases/jvm_record_extends_class/negative.kt': '241163668a2438c3701702977e153aaf355fd2f9db06bc0a4b70e30878165854',
 'providers/compiler-fixtures/cases/jvm_record_extends_class/positive.kt': '161b3c756294de45a5a0c1a45e14cedc8ca15a753987b4ef6335f828dd698c7d',
 'providers/compiler-fixtures/cases/jvm_record_not_val_parameter/negative.kt': '21afc3f91a6e01a9c991cb8a5bbec10e5a910b6e7455aed448c6210683bd63b5',
 'providers/compiler-fixtures/cases/jvm_record_not_val_parameter/positive.kt': '92055ea6a5f487c36a93a08fa7be1b8fd83289450e5ff7c8f0d104905f399e49',
 'providers/compiler-fixtures/cases/jvm_static_not_in_object_or_companion/negative.kt': 'b86ef61cf9806a9d0b0b6938a9d6973a57a79f1f5a0fb96ce01f739ab31e523f',
 'providers/compiler-fixtures/cases/jvm_static_not_in_object_or_companion/positive.kt': 'e7be9832a7faf93fd48fea27bed75f836145ae746c4cd0459c250549ca5f9a88',
 'providers/compiler-fixtures/cases/jvm_static_on_const_or_jvm_field/negative.kt': '31ad1f95ac2e20f7b1ec0c855c1b51e35a43211b4f7947bff674e1dbee480466',
 'providers/compiler-fixtures/cases/jvm_static_on_const_or_jvm_field/positive.kt': 'adfe1c68708396bf2cb5ef374001f323792ab28ab449c7e4b001b07028093b9d',
 'providers/compiler-fixtures/cases/named_parameter_not_found/negative.kt': '5c20a523e3eb890796ef6c3b21940fe0f16b0bbd1e64f6e9e1aedd3140df4545',
 'providers/compiler-fixtures/cases/named_parameter_not_found/positive.kt': 'd8a874bc538fcd2399712682d6d6b255c0ebedb495c8c2762a912ce567223706',
 'providers/compiler-fixtures/cases/no_else_in_when/negative.kt': '4aafd331666be6854bf57d3642bd7257d60163803b0dc3ae982691bd59dd6b5c',
 'providers/compiler-fixtures/cases/no_else_in_when/positive.kt': '5d8f5be96dcbe428c44cf261201a0a02afc48c4734e8e7850c117bfd3d14e575',
 'providers/compiler-fixtures/cases/no_get_method/negative.kt': 'ab955ae686e4bf7464398cb09b061374d4e694f3fc533e1715cb19f78f44b2a2',
 'providers/compiler-fixtures/cases/no_get_method/positive.kt': '36c4d1ca6646d98ac45c1654a8077d680371f13c73dfc69358bd8d32a63c5d05',
 'providers/compiler-fixtures/cases/no_set_method/negative.kt': '92a9cdd770def96626bee6bff0f49e7cf7a98aeb5876c2537229001c634367e4',
 'providers/compiler-fixtures/cases/no_set_method/positive.kt': '4ddbce3291b3c0b9bbb46bf71449d7658939a00a218977c86b2ca01e7a19055c',
 'providers/compiler-fixtures/cases/no_value_for_parameter/negative.kt': '168700c9d126cd38057b379840e8f4d722825e3d41676619ad3b255562db536f',
 'providers/compiler-fixtures/cases/no_value_for_parameter/positive.kt': '870e8886fa5dbdf28e79b57226ef0c303b2672f1304d8adf7f090a97c0f0634d',
 'providers/compiler-fixtures/cases/non_data_class_jvm_record/negative.kt': 'e28b0b5ccaa935d7ffe5c80d16fab36fc943211fd205b4368c4836fda322ce57',
 'providers/compiler-fixtures/cases/non_data_class_jvm_record/positive.kt': 'ca919b1c863819f48f365752a348b440fb74580576dd3bb8d43ed4dc01bcfb37',
 'providers/compiler-fixtures/cases/non_final_member_in_final_class/negative.kt': '99ffd4d2b349bb47949b845c583c0850f06ae0c17c7d78b02eccd24f8ba425aa',
 'providers/compiler-fixtures/cases/non_final_member_in_final_class/positive.kt': 'd6066a54ae1680c843fc8a0ee56f5ef2b8e734c00baea89395eea6e7ada1bb2b',
 'providers/compiler-fixtures/cases/non_public_call_from_public_inline/negative.kt': '21227edfa94aca82d8addf2b8ebf0298f13cfeadb702205bb701c19ada5acc39',
 'providers/compiler-fixtures/cases/non_public_call_from_public_inline/positive.kt': '79a173871e46fc0187b97876842eb714fd88deafaa2b75b88babfb0ca2604ab0',
 'providers/compiler-fixtures/cases/non_suspend_overridden_by_suspend/negative.kt': 'a30080c3ded18a3d9ab3e9e21e8ceca079707db1d5bbfbcae62c64fec2f6d09d',
 'providers/compiler-fixtures/cases/non_suspend_overridden_by_suspend/positive.kt': '7c50a15ac211eeeb65f0854fffdea32d7802d8401d9f3b340c3a761b7ceaf05b',
 'providers/compiler-fixtures/cases/non_vararg_spread/negative.kt': '8dac2d7a498631eeb6e3e235cf26e2a0b01387115c5690db91dec2f50a96a274',
 'providers/compiler-fixtures/cases/non_vararg_spread/positive.kt': '02dd78ac96f8906436e5cb2609f34fa2416cba1b665deac09abd6fedde4c6f90',
 'providers/compiler-fixtures/cases/nothing_to_override/negative.kt': 'f99dd8d6607f2fb1d0431540f440ca63bf07674ccb34109c53fd78bcba165bc5',
 'providers/compiler-fixtures/cases/nothing_to_override/positive.kt': 'c3c034d9fc35d2bc2705c917dd2b28951d5ad96cf1321fac574a270317e105d7',
 'providers/compiler-fixtures/cases/null_for_nonnull_type/negative.kt': '1bbfb915c432d6f14bcb4086ec22d6b1731ea2a640f39ab7f6773faca66b1fa9',
 'providers/compiler-fixtures/cases/null_for_nonnull_type/positive.kt': '960419f714930897b4c5910942d4cdedc2804949357043766e0ff14c7f508a5e',
 'providers/compiler-fixtures/cases/opt_in_argument_is_not_marker/negative.kt': 'e50ff533619e6ff00a99a6e43dc20c6f000b9fd87fbb6a4f9021f972b8a45b45',
 'providers/compiler-fixtures/cases/opt_in_argument_is_not_marker/positive.kt': '38ba5bb448403d554b756955223d43d3dd75b185ff18013938db270d0a030f36',
 'providers/compiler-fixtures/cases/opt_in_usage/negative.kt': '02f1aad6d372e718bcff248351c9d5bd2419326ee7fe42f482bccda2e713b2b1',
 'providers/compiler-fixtures/cases/opt_in_usage/positive.kt': '06fd3b9727ceed8f2520f7e689cef62ac42744a660c90f30bc15bfd9603a86cb',
 'providers/compiler-fixtures/cases/opt_in_usage_error/negative.kt': 'b4b78dbbea7eb6e1409d6d60978cd86ad17e8cc43110d10a0570ac7b48f333c3',
 'providers/compiler-fixtures/cases/opt_in_usage_error/positive.kt': '961b0490b56a48fdc2100ed7eb468acf40e484399c9900187999bcc3cb73d8d4',
 'providers/compiler-fixtures/cases/opt_in_without_arguments/negative.kt': '9a6e4034e60a1e94cc3ac038451e0dc242561f0dec33de64b2b8d8ff2a9ce243',
 'providers/compiler-fixtures/cases/opt_in_without_arguments/positive.kt': 'aaaf0943bae85de13456e717a2441ee0493ab5299f3e37b1dd92c0e21f1b0958',
 'providers/compiler-fixtures/cases/overloads_abstract/negative.kt': '4f1d0d2bc8de320edae518649f70176df7bfc8b81f9aed36eee221db6270cb81',
 'providers/compiler-fixtures/cases/overloads_abstract/positive.kt': 'f267bce2c4c7a1f302626a535208c9747476f01f6497dff5ca2fb6285d373654',
 'providers/compiler-fixtures/cases/overloads_interface/negative.kt': '5988fb3c335705efc1ed053807422f64fb2543c25c356f9aa8a7eb428a4d54c3',
 'providers/compiler-fixtures/cases/overloads_interface/positive.kt': '53ed21646366bc097838318d558128412c41861ff4e687205701d998b89ccda5',
 'providers/compiler-fixtures/cases/overloads_local/negative.kt': '5d28186003b55ff4fe19e890727e82a8bd7e8629157674d1027be373b55ced39',
 'providers/compiler-fixtures/cases/overloads_local/positive.kt': '7ce3e8de373e42c4f495e315fa98ac17c70a7c6e9a2972c4c9300a35f946519d',
 'providers/compiler-fixtures/cases/overloads_private/negative.kt': '431455b51295e161ae0b75398f5c25660cd977afafb95f98a92255ebd218c3b8',
 'providers/compiler-fixtures/cases/overloads_private/positive.kt': 'e3456298b600a0d699f475fc1a956b5d3997b5ca90bb57291fad25f895219f44',
 'providers/compiler-fixtures/cases/overloads_without_default_arguments/negative.kt': 'cc302a10c855f2dbe851317a4e38aac4b1ce9d5e32549b84a86f9ce20ffbd6d3',
 'providers/compiler-fixtures/cases/overloads_without_default_arguments/positive.kt': 'c46dd40de00400bbe0b26406c1c6b4dcaf19e757742c813feb15f72c2d53f74d',
 'providers/compiler-fixtures/cases/overriding_final_member/negative.kt': '96fdafe2fe75399f2e2fed4518c3b7270106e9ff707f6a8015490a9d95007365',
 'providers/compiler-fixtures/cases/overriding_final_member/positive.kt': 'd2cd18fc25117108dc0b91292b475922d84da990e181034642f953c35cc1ff66',
 'providers/compiler-fixtures/cases/parameter_name_changed_on_override/negative.kt': '873e41c5527ce865e11eaecc013753044895676efae61ae5468a5af9c1b229eb',
 'providers/compiler-fixtures/cases/parameter_name_changed_on_override/positive.kt': 'b51e3b7426bd8a217caf63340e055a72de54da6d02b505feb8eba0603055e026',
 'providers/compiler-fixtures/cases/property_type_mismatch_on_override/negative.kt': 'b2621871c6adc5fc5265d74e79fe50f976d187ddb2848f5c94b5e32e261d3bcd',
 'providers/compiler-fixtures/cases/property_type_mismatch_on_override/positive.kt': '6270fd4527663b409fd91ae1b7208fd421b5d48c64b87c45b6e1dda7dca6ced7',
 'providers/compiler-fixtures/cases/recursion_in_inline/negative.kt': '48261b52380e0b7943b973b0f113b11d037467e000fd86025911fb7ca83d545e',
 'providers/compiler-fixtures/cases/recursion_in_inline/positive.kt': 'c15fd1196e4dae01375c2ec782169159a3e788e718b983f49844c94f8f7dccfc',
 'providers/compiler-fixtures/cases/recursive_typealias_expansion/applicability.kt': '78ef670e4512bbcad9866de3d57e3a416888fe9b7cd738511e228c380b7d6140',
 'providers/compiler-fixtures/cases/recursive_typealias_expansion/boundary.kt': '27546e583f4ff54393af9d0ceaaf8e7d1806c884bd03af1489180bf8bad1d19b',
 'providers/compiler-fixtures/cases/recursive_typealias_expansion/negative.kt': '8dac0869823da179a27e8c852fb2c07df7f1394301a466cd53092a3771c653fb',
 'providers/compiler-fixtures/cases/recursive_typealias_expansion/positive.kt': 'be045c201b98296f8b9c09e60a2c3c7e737a26342ef93afcac2e120edf2e3593',
 'providers/compiler-fixtures/cases/recursive_typealias_expansion/suppression.kt': '8e089e9e6464df1fe29d6124f56289d057f0060e9bd35536c42d162b1bc40e1f',
 'providers/compiler-fixtures/cases/return_type_mismatch/negative.kt': '0193c131a5bb90f961699f6da6c2803a41a7b7c0986471468838da0a425bee1d',
 'providers/compiler-fixtures/cases/return_type_mismatch/positive.kt': 'c986ad1553c671aae2f601948702d049951977d9249a240493d7fda39f3ad88e',
 'providers/compiler-fixtures/cases/return_type_mismatch_on_override/negative.kt': 'fd576f80387a5b12ef96b923974aca2584e4788674301027c4d362a7a0bf8258',
 'providers/compiler-fixtures/cases/return_type_mismatch_on_override/positive.kt': 'ff8b134dc63f1d8b7170ce127a8136e6d1971210b58b527477fadf0ee8742f88',
 'providers/compiler-fixtures/cases/smartcast_impossible/negative.kt': 'd286b894b93c8c307e2cc3ba26584c30322ac62817f8f9ed7cde511fc0458dc4',
 'providers/compiler-fixtures/cases/smartcast_impossible/positive.kt': '85b8c106b40ec2c8c0989f71d4145ee38e6c4c1eb86d7ea17412752dc169c2c5',
 'providers/compiler-fixtures/cases/spread_of_nullable/negative.kt': '54133866b669f4c598a416a2085af7104e0005ba0b2418e94eccc5dbd3d10db0',
 'providers/compiler-fixtures/cases/spread_of_nullable/positive.kt': 'fbb0192f4de9a69c5229ada9655571d9d4c143aeb48e6b5d6873b9a9c1c44d4d',
 'providers/compiler-fixtures/cases/supertype_not_initialized/negative.kt': '1af2c7a56767acb5fff59826c456716e909197589b59062cf334b727ec85ee13',
 'providers/compiler-fixtures/cases/supertype_not_initialized/positive.kt': '62107df1f76f991026287bef42b7482fcf1c1943000716facddf3ee8cc876683',
 'providers/compiler-fixtures/cases/suspend_overridden_by_non_suspend/negative.kt': '6d7d0cca71beefd93935049b296a50ba4a5833a3ed8b2d783168fe2d80424f15',
 'providers/compiler-fixtures/cases/suspend_overridden_by_non_suspend/positive.kt': '5bb7d7a6b9c75aa8f35f3ef6ffb8a08f5771790ec9f6567043a7aedd480839e8',
 'providers/compiler-fixtures/cases/synchronized_on_abstract/negative.kt': '2dffa2b068a6f44545603aa4cf8246779e11c97424b0302e9f1a26bcccb64d33',
 'providers/compiler-fixtures/cases/synchronized_on_abstract/positive.kt': 'faa782995219cd8213520c430ed99ab2130b4102fe5bc1f11388663afbca6145',
 'providers/compiler-fixtures/cases/synchronized_on_inline/negative.kt': '8e69a7864553d650fcc43b3f98dd43dacda874d8bba77a57997f6c63fc12a81f',
 'providers/compiler-fixtures/cases/synchronized_on_inline/positive.kt': 'deed6c4c156441afcd5541bf99fe17e89f5691d5248bc974a67841c47457f8c5',
 'providers/compiler-fixtures/cases/synchronized_on_suspend_error/negative.kt': '08f28afe892afd4f8c23ea8290721d41ea847afd542ad8bbb46baed3aeea20fd',
 'providers/compiler-fixtures/cases/synchronized_on_suspend_error/positive.kt': '38cf0b428a803a6d92cf436b45e45b21104a460fb6ab1e3c33d8b7e5b6a22d3d',
 'providers/compiler-fixtures/cases/throwable_type_mismatch/negative.kt': 'c61d63da59e56f7300bd98874b17f4bb2d257691e9dcbc5944f8b3a0308ac88f',
 'providers/compiler-fixtures/cases/throwable_type_mismatch/positive.kt': '67e0af1219e84ed020aaf85c928e7e0c30c3b211eda29dfb0dae7685eedbd72d',
 'providers/compiler-fixtures/cases/too_many_arguments/negative.kt': 'd07b0b6ce2d154e827627cd6ce7b2f0ad2a8c750712e189875f1edd3f875ab5b',
 'providers/compiler-fixtures/cases/too_many_arguments/positive.kt': '94f3450e4cccca25c787bd9f2f34518d4dd9bb03e9974d372e122005be9ed7a3',
 'providers/compiler-fixtures/cases/type_variance_conflict_error/applicability.kt': 'b964d3824925310f5c878cef028d4f6fcece9e838f42962d25b91e59db9c9f1f',
 'providers/compiler-fixtures/cases/type_variance_conflict_error/boundary.kt': 'e84683626556459de8661d278d632556b49a798f54863b088470aaa037b998b6',
 'providers/compiler-fixtures/cases/type_variance_conflict_error/negative.kt': '0b668d62c4e2561900625e51b880a13ca166824993979ffe525855a7feac17ab',
 'providers/compiler-fixtures/cases/type_variance_conflict_error/positive.kt': 'a5b2acf2fc4edf6aa75e4a1efbb4cc8a25a521e6e50d1ed7029a4f180a23519e',
 'providers/compiler-fixtures/cases/type_variance_conflict_error/suppression.kt': 'a72f4f74a5492cd76ed5d8095169c417371bde406902a373929b9b035f4d73e9',
 'providers/compiler-fixtures/cases/unchecked_cast/negative.kt': '9344cad147bb3d029a687385d7156d6da0e74947a272ce9d302925062e430c44',
 'providers/compiler-fixtures/cases/unchecked_cast/positive.kt': '72f1209b89c0a082f28c63eb8a89b391fb20e8e7cbd61da5ad48688cbd6e7160',
 'providers/compiler-fixtures/cases/uninitialized_variable/negative.kt': 'a38a03ced2e492e0d67299b1ab4b12ff674435bf080b2dee38d4d67f8ff544e2',
 'providers/compiler-fixtures/cases/uninitialized_variable/positive.kt': '50b19de9b95ef105643f1515b5d12b5b7b6074329a982e913378533e3c946465',
 'providers/compiler-fixtures/cases/unnecessary_not_null_assertion/negative.kt': '3c9dbcee9ae1e837ef55c6286d76490ab04384b3f4a945fe681650429aa88e4b',
 'providers/compiler-fixtures/cases/unnecessary_not_null_assertion/positive.kt': '8f38ac71ea8f36cf3733fb2257e6dab26383b212ee7a86171e0dd5ca67ad3ea2',
 'providers/compiler-fixtures/cases/unnecessary_safe_call/negative.kt': '809ccd608c17ae112809fd952f506cf1166196b008acd1189277b9b20af5b159',
 'providers/compiler-fixtures/cases/unnecessary_safe_call/positive.kt': 'ccc966cea89a4f9041b23f81c5fad758de0d54012f7998396b8399b5874bb548',
 'providers/compiler-fixtures/cases/unreachable_code/negative.kt': 'c116121d5e27e7f351736fb701838c0ab3f793c25d81e8be4ddadb11c8b95af4',
 'providers/compiler-fixtures/cases/unreachable_code/positive.kt': '4c3c72e1d68b9e2c168c2a029a3aaf04f707e5753f5a80cc1932134c03dc1c8f',
 'providers/compiler-fixtures/cases/unresolved_import/negative.kt': '312ef33f114811ca282e51a44c617bcdc91838f79e1d86731355f2bc57c7a919',
 'providers/compiler-fixtures/cases/unresolved_import/positive.kt': 'ed407fd6fcb4501abac71543be3b3dbb5eb3d69c3016e900c6e52157d22dfaf7',
 'providers/compiler-fixtures/cases/unresolved_reference/negative.kt': 'b806e34702c37d638d0a6452ff9b6c90484a256f6e285ca51a08ae33b5f0970e',
 'providers/compiler-fixtures/cases/unresolved_reference/positive.kt': 'cc58d834595d3f05f4dad1f3a568ea60561c63a08974847a5024d627cc3e940a',
 'providers/compiler-fixtures/cases/unresolved_reference_wrong_receiver/applicability.kt': 'fad5f520c0fa0cc9e72b3cf3a88c5ee51eef3f44a6a263a4aeb1de1dc123c2b5',
 'providers/compiler-fixtures/cases/unresolved_reference_wrong_receiver/boundary.kt': '05f4412ed537aefc23824dc5223a8f09ed7c1474944411bfc884c895af284c0f',
 'providers/compiler-fixtures/cases/unresolved_reference_wrong_receiver/negative.kt': 'd0e173375718e257dbc8a269ace8a1f0f8b61d97905c482534f4f083f4dd551d',
 'providers/compiler-fixtures/cases/unresolved_reference_wrong_receiver/positive.kt': '53aeeeb8bc32c49c8f24a0baa483182570093924dab33689a9424e2533409dfb',
 'providers/compiler-fixtures/cases/unresolved_reference_wrong_receiver/suppression.kt': 'c5de166c4603fef3470e978bf72a436885a11b05fbf4227291388b5026722dcc',
 'providers/compiler-fixtures/cases/unsafe_call/negative.kt': 'f2a9d838a60636e6383e842c6f819fe32e9ecd60fee6b2d47215f0a02d66b9ca',
 'providers/compiler-fixtures/cases/unsafe_call/positive.kt': '4f903026cb19f77c8a7e8fa600cd8bbc75609f9aeb03d7bbe6297bbac5d193f0',
 'providers/compiler-fixtures/cases/upper_bound_violated/negative.kt': 'd48e64516ae15b1c4fb1fca18bcca2b2e2992fe8b300b80bc1793b5b10e30be3',
 'providers/compiler-fixtures/cases/upper_bound_violated/positive.kt': 'd4c523be141bce9e62df159a14cb7e31c3bc7842cedca3630dca17b2223b0f50',
 'providers/compiler-fixtures/cases/useless_cast/negative.kt': '4963806bb8acc6e0d49d6e8e711c701aae8a5fd25420a013c6de50c72dfd9d28',
 'providers/compiler-fixtures/cases/useless_cast/positive.kt': '08e2747e355aae41d2698f1b26518fb549e7405163d4d3a5d9099409040877b4',
 'providers/compiler-fixtures/cases/useless_elvis/negative.kt': 'd1e45039824ead644fbb8210895e883feb8667172da25490398b454e235a8eec',
 'providers/compiler-fixtures/cases/useless_elvis/positive.kt': 'a6ac2f1ea9e64a4a1077ada8e42a4b8b655495fc5f49abeb6d59a660c96c1de2',
 'providers/compiler-fixtures/cases/useless_is_check/negative.kt': '973eb206f6e30cdeb950eb892ebac1eecca0393c697472a116a1ffa80b7f9eb4',
 'providers/compiler-fixtures/cases/useless_is_check/positive.kt': '5f8b9f40b8453cbce06352ab4155f1193bfad26ce831afcb83b03d8eda6b2d1f',
 'providers/compiler-fixtures/cases/val_reassignment/negative.kt': '3ad282b3a83b8f21f31eb83a10aee22ad8b21a5c5b512e2694c52f85fc5d34fb',
 'providers/compiler-fixtures/cases/val_reassignment/positive.kt': '3bf39cc517c492848601e06ff06ee5f6ec0fec12b80b99309a129cb8a8a11ac3',
 'providers/compiler-fixtures/cases/value_class_without_jvm_inline_annotation/negative.kt': '066b585e95d08c5459a48bd9fc34655bae4c2eda1608c4c69df3071c31c1441c',
 'providers/compiler-fixtures/cases/value_class_without_jvm_inline_annotation/positive.kt': '1b1efa55fa59f3d9c9a0e71bfeefa00bf443dd1cedf5176a7232ea29beabe81b',
 'providers/compiler-fixtures/cases/var_overridden_by_val/negative.kt': '5886b8e269f84ef67fcf51fbf97e892436708f478aae7e6d1fd1439bea7a46fa',
 'providers/compiler-fixtures/cases/var_overridden_by_val/positive.kt': 'e2ec7d91db91e8bb5319dbd030cd0639c69b19f204d007d7df2b45f7ad478979',
 'providers/compiler-fixtures/cases/virtual_member_hidden/negative.kt': '794f48d4c3dad20b906ef0cc6eebc6ca076ec03f497832cdedce5d326ede1fbd',
 'providers/compiler-fixtures/cases/virtual_member_hidden/positive.kt': '64cc1688a01fa924e6839e1bde0b481572024825f14f64e1fa954f293a9c7572',
 'providers/compiler-fixtures/cases/wrong_annotation_target/negative.kt': '6fbe78a340fee808c5cfdfa929359a9237e370718c25bafe144707d4764a244a',
 'providers/compiler-fixtures/cases/wrong_annotation_target/positive.kt': '2b118d54a8d08ec37128a443af8394088f1b9006fbf5bde609e4d69fc801f2cc',
 'providers/compiler-fixtures/cohort-plans/diagnostic-breadth-README.md': 'f28007321273fafbff8b4562977d72b15f8607cacc5359db5162ecae0818148b',
 'providers/compiler-fixtures/cohort-plans/diagnostic-breadth-gap-inventory.json': '9cbf6743f83d25dcef58325997552539e7c0116efff3a8523ab58c87df456c54',
 'providers/compiler-fixtures/cohort-plans/diagnostic-breadth-source-evidence.json': 'ed4e9f5c81d0ca7383fe38b41da4d4a23e419b7afd5ecee1e178fa9ffc9b546b',
 'providers/compiler-fixtures/cohort-plans/diagnostic-breadth-v1.json': '4bbb0528fcd0ab9b7c5c8fb5252bbe03a481666abed1ec5bb29b6632bed8b8bd',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-README.md': 'a8bd4914cdc7d337add5e1eed8398ebbf32ef8d9f2f0c4ea2a182a6785d62db7',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/iterator_on_nullable/applicability.kt': '7677625ababb895156875d6f172ec555c116515a1f6ad38b90e0bab3d83220f5',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/iterator_on_nullable/boundary.kt': 'bffaee3477b8cd269a3edc8d83767173f8bae7007579a520f71fa8934e45b3a3',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/iterator_on_nullable/negative.kt': '9b4911e6de82cb703c30afc824b75fa66bbd6946504363c232e64107a62fec04',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/iterator_on_nullable/positive.kt': '3e571b48b075a567cde4d06b304159ebce2ee98c5123a33277c630498ef86163',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/iterator_on_nullable/suppression.kt': 'e82cc0ca2a434fa8cd893a10b651d02db740c6b160b263af4a9d5b26e3cd5079',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/unexpected_safe_call/applicability.kt': '5454aca7e046292a36febc10710550a262dfe661f2372ab04e164c568ecd2f7b',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/unexpected_safe_call/boundary.kt': 'eb6a0212a10caf44fdd1447f19a4f32e29134f4fdf3aae9a01f99fad7f83eadf',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/unexpected_safe_call/negative.kt': 'caac88241f68eb0ab07f681408f50155fea8bb8d8bbc282b54da9b8303a7797c',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/unexpected_safe_call/positive.kt': 'abcfbe94c77b33690b2ced052fff55c0c6a14db41b248ca241f81f09c271a3c4',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/unexpected_safe_call/suppression.kt': '33c9235a65140044479c14f55f8dc04429e0942de4af692cd441a8280ec280d2',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/unsafe_callable_reference/applicability.kt': 'e651fdc1199a6dc802b0930ee6ca8e4a76bc7e64aeb84006d9b86059e1629b2c',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/unsafe_callable_reference/boundary.kt': '76ac4763a73a7394707edcff444fcf9c6305137b064617f61591135ab526cedc',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/unsafe_callable_reference/negative.kt': 'ae6150502d1274c9298f3f8beac7bb2a1d76abd6493f72d2a61023506c654c73',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/unsafe_callable_reference/positive.kt': '5a2f487292a8f3f7eb99450a46701eb7546e74879eaafa4b24c702de3ebcac7c',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/unsafe_callable_reference/suppression.kt': '09af74f7a83980315d267ce8b663be456707b5f342214d9fe8eaa723711acc4c',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/unsafe_implicit_invoke_call/applicability.kt': '1894ba6be39d7b43f2d85970182c4d2d0839c27be4d85e8b10238815ad0cfad6',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/unsafe_implicit_invoke_call/boundary.kt': '8370b165cbf685e814ae7a02d6ca45d7e5230b349745dde556d95913c2463a51',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/unsafe_implicit_invoke_call/negative.kt': 'afe94061f0e38539f5c6af16eecf10b3e19544725d760ec09c784e310dc3cf9b',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/unsafe_implicit_invoke_call/positive.kt': '52ce436172dae231416bc02452151f569f8760cb9c0439a1bfac2b1f080ee6ea',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/unsafe_implicit_invoke_call/suppression.kt': '8558e3a92bc5f4586a9eae836cf272687617318b483f269fdf3296b1b427ebbc',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/unsafe_infix_call/applicability.kt': '7939d9a41264cb8b31219092b0f31ffee70b32f8e7a54dad791c08fbd8f95dd9',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/unsafe_infix_call/boundary.kt': '843e37f6f343c5524092c12c4e30d2f29c8209b15fded1463353904c1f1fd2f4',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/unsafe_infix_call/negative.kt': '653b41cebbda3d5eabe8405cb428ee9b328d9b573ebc4d8511297efa805ca562',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/unsafe_infix_call/positive.kt': '6285cb534f872d68c0461217d1eb9bb080eb7958bb0a3cc1b59b3bfde2e9496e',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/unsafe_infix_call/suppression.kt': 'e3f44e589b55ed9865957cb93d0aa31e6cb288ef3531ccc3531186816b89be0f',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/unsafe_operator_call/applicability.kt': 'c8cd8fe0d4fb73f2c35f613f047bcea6337a750ff06ca5fb0593c021384645bf',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/unsafe_operator_call/boundary.kt': '204d5ea963c341f84b4d8eed4b153e4996baf09149653292c74301ac896b99ea',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/unsafe_operator_call/negative.kt': '30f620138e400107d6a75fa5a5914c16e6b663f84715de7bbb2a1797d2bb2744',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/unsafe_operator_call/positive.kt': '0b4b77531c01ff2135f6d08e96d315a6e7501642e28999f01bade91a236a63ba',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/unsafe_operator_call/suppression.kt': '8982dbd99ed2256c06190a631d319c8322c567cef00da01dda6634f748b60946',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-gap-inventory.json': 'dbdf33758d612786e9422d45551cdb3fda43f3f4a3fad57a7e3fab47f6c03a70',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-source-evidence.json': '2a453246ad9db2c0499c5c2c9cd488261493a786a5185618d91beafeb83ea0b5',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-v1.json': 'e8d9eb399768454c0ad648d2b2f61024e0924cc3589c6f0c3bddbadf2fe1af44',
 'providers/compiler-fixtures/cohort_expansion.py': '2dd0d0323b507da9f4ff3e758ab0951f53fcdf196a46006331e5e33681f33edb',
 'providers/compiler-fixtures/manifest.json': '4854aa356caba6e90b48e10e333bf32828460dc64b04dd0d6b86db3653d49774',
 'providers/compiler-fixtures/nullability_protocols.py': '6e2774bd505110cca4e07eaddacd00d5a3e0a626a7a5e7b238da1798d9840ab1',
 'providers/compiler-fixtures/planned-kmp-cases/actual_function_with_default_arguments/negative/common.kt': 'e7f1ddb197e681fff8651989e393df7a27ee16cbb2d44f62e9b9234a718ea7dd',
 'providers/compiler-fixtures/planned-kmp-cases/actual_function_with_default_arguments/negative/jvm.kt': '7adf1029d48613c6d690d60284d6eb4b041b11b03115545e3ecef5ee91c639dd',
 'providers/compiler-fixtures/planned-kmp-cases/actual_function_with_default_arguments/positive/common.kt': 'e7f1ddb197e681fff8651989e393df7a27ee16cbb2d44f62e9b9234a718ea7dd',
 'providers/compiler-fixtures/planned-kmp-cases/actual_function_with_default_arguments/positive/jvm.kt': '098428a49f2018668521d9f1c1ffd84a5f801bbfd6b8deba4101fff68ecda85a',
 'providers/compiler-fixtures/planned-kmp-cases/actual_missing/negative/common.kt': 'c1e89d7956c0bc5b96f832ae53da72f72f14bbe77d6096950f3fb94b660f0c94',
 'providers/compiler-fixtures/planned-kmp-cases/actual_missing/negative/jvm.kt': '9827156000176fcbeb1696c74af9eaa6c3737a8908723fa6b2dcf8502e20cc21',
 'providers/compiler-fixtures/planned-kmp-cases/actual_missing/positive/common.kt': 'c1e89d7956c0bc5b96f832ae53da72f72f14bbe77d6096950f3fb94b660f0c94',
 'providers/compiler-fixtures/planned-kmp-cases/actual_missing/positive/jvm.kt': '442de7a7a8d4b2ff896d5648fead923bdc4fcb4d86a80badc10516574b516536',
 'providers/compiler-fixtures/planned-kmp-cases/actual_type_alias_to_nullable_type/negative/common.kt': '8280d624a983cc84fdb0a4fb34f58a71d59ee7137b473b8cd338c0b7620580a6',
 'providers/compiler-fixtures/planned-kmp-cases/actual_type_alias_to_nullable_type/negative/jvm.kt': '7290b715996d5c6ab087c914235c9e05e0a56476263c6f2e6592b780af4b40e6',
 'providers/compiler-fixtures/planned-kmp-cases/actual_type_alias_to_nullable_type/positive/common.kt': '8280d624a983cc84fdb0a4fb34f58a71d59ee7137b473b8cd338c0b7620580a6',
 'providers/compiler-fixtures/planned-kmp-cases/actual_type_alias_to_nullable_type/positive/jvm.kt': '25f6d526f1314d9580ca10e0b9035a94541745ae6bcc636ccd69210966bd8049',
 'providers/compiler-fixtures/planned-kmp-cases/actual_without_expect/negative/common.kt': 'f8072a229d219f2b38567a293b9bdf0b46c36c264608a9a376bec1bcefdbac4a',
 'providers/compiler-fixtures/planned-kmp-cases/actual_without_expect/negative/jvm.kt': 'c0838a70c3ef17477508e13bb99aebb0ee707f0240062bc543c29550cd08e775',
 'providers/compiler-fixtures/planned-kmp-cases/actual_without_expect/positive/common.kt': 'f8072a229d219f2b38567a293b9bdf0b46c36c264608a9a376bec1bcefdbac4a',
 'providers/compiler-fixtures/planned-kmp-cases/actual_without_expect/positive/jvm.kt': '89f8c1404f66a7f3cd7f6b120522ec77721573ac976d1d19c13ebcbaf4927f8f',
 'providers/compiler-fixtures/planned-kmp-cases/expect_actual_incompatible_parameter_names/negative/common.kt': '9c091f7e1ce76cf587c919d33f8e1826b63af8e6b341d0766670e4acb1a539d7',
 'providers/compiler-fixtures/planned-kmp-cases/expect_actual_incompatible_parameter_names/negative/jvm.kt': '423baae8a695dc7935487f08dd3aa13148e83f3f7e2b0a46f4033ebd6f6e6575',
 'providers/compiler-fixtures/planned-kmp-cases/expect_actual_incompatible_parameter_names/positive/common.kt': '9c091f7e1ce76cf587c919d33f8e1826b63af8e6b341d0766670e4acb1a539d7',
 'providers/compiler-fixtures/planned-kmp-cases/expect_actual_incompatible_parameter_names/positive/jvm.kt': '25667044f90ac9210f11d32e192125ee7e7796f3423cc81d7ca82462e4930f1f',
 'providers/compiler-fixtures/planned-kmp-cases/expect_actual_incompatible_property_kind/negative/common.kt': 'aa338d0fd3226c698f7034a0fa2dbd0f346d064299f5434152709ba9110e5221',
 'providers/compiler-fixtures/planned-kmp-cases/expect_actual_incompatible_property_kind/negative/jvm.kt': '6fcfc547634d0d4c562994018d9e8fcea80a9a598825123d0eaedebe1f85fa38',
 'providers/compiler-fixtures/planned-kmp-cases/expect_actual_incompatible_property_kind/positive/common.kt': 'aa338d0fd3226c698f7034a0fa2dbd0f346d064299f5434152709ba9110e5221',
 'providers/compiler-fixtures/planned-kmp-cases/expect_actual_incompatible_property_kind/positive/jvm.kt': 'd80892d07f9a9b44c5794967a8fd66a46b594962441209e0834e6998172b9d87',
 'providers/compiler-fixtures/planned-kmp-cases/expect_actual_incompatible_return_type/negative/common.kt': 'fb5bc1e6c7cd9b0fe7d5160eaa60496d9bbfd912f73a970ec31cc07a6561edcc',
 'providers/compiler-fixtures/planned-kmp-cases/expect_actual_incompatible_return_type/negative/jvm.kt': '30aca5e994c03968db323d8a960e7a2231b7c3ae8a016be0704ac2b7f2fe648d',
 'providers/compiler-fixtures/planned-kmp-cases/expect_actual_incompatible_return_type/positive/common.kt': 'fb5bc1e6c7cd9b0fe7d5160eaa60496d9bbfd912f73a970ec31cc07a6561edcc',
 'providers/compiler-fixtures/planned-kmp-cases/expect_actual_incompatible_return_type/positive/jvm.kt': 'a8c7ccec426e9d07b8cb30b413752f04c457e402929ca101766ec076725a217f',
 'providers/compiler-fixtures/planned-kmp-cases/expected_class_constructor_property_parameter/negative/common.kt': 'eebbd627632a00c82581b83ad31f3e5820bb3c60fe2937c7b488a9f61f6a8f44',
 'providers/compiler-fixtures/planned-kmp-cases/expected_class_constructor_property_parameter/negative/jvm.kt': '2be5886adad99217e7583cb8fb071807ebed97a5d8cec574efa34e94bc43a243',
 'providers/compiler-fixtures/planned-kmp-cases/expected_class_constructor_property_parameter/positive/common.kt': 'd96df699885265a2504226d06533bcd59ad67df451ab09f37892e3109d1b925a',
 'providers/compiler-fixtures/planned-kmp-cases/expected_class_constructor_property_parameter/positive/jvm.kt': '2be5886adad99217e7583cb8fb071807ebed97a5d8cec574efa34e94bc43a243',
 'providers/compiler-fixtures/planned-kmp-cases/expected_declaration_with_body/negative/common.kt': '23bd28a19b7b6dc4d6cb858fb7ad0d7198e54685713acd914f37bbcff102c183',
 'providers/compiler-fixtures/planned-kmp-cases/expected_declaration_with_body/negative/jvm.kt': '9ef05ed9e91d36f8bb9c154846e634acb09108ca9df0b6b0e76dc014cb3a8f3f',
 'providers/compiler-fixtures/planned-kmp-cases/expected_declaration_with_body/positive/common.kt': '935093e9ac8918ff762740db6975f319adc71fd6ed77a0c82951649adfa72139',
 'providers/compiler-fixtures/planned-kmp-cases/expected_declaration_with_body/positive/jvm.kt': '9ef05ed9e91d36f8bb9c154846e634acb09108ca9df0b6b0e76dc014cb3a8f3f',
 'providers/compiler-fixtures/planned-kmp-cases/expected_delegated_property/negative/common.kt': 'bda05c0057f7d8a101586de6c94271dd0310b44195e3001b0da5327f834b275c',
 'providers/compiler-fixtures/planned-kmp-cases/expected_delegated_property/negative/jvm.kt': '4bde8692249962d86f7db9c74c064204be9f9f43f69d83d593a2b4961d6b9c20',
 'providers/compiler-fixtures/planned-kmp-cases/expected_delegated_property/positive/common.kt': 'a7bdd91853cfc39144237c95e87762dbd37dbb69bb78f517082f9af94519b9e6',
 'providers/compiler-fixtures/planned-kmp-cases/expected_delegated_property/positive/jvm.kt': '4bde8692249962d86f7db9c74c064204be9f9f43f69d83d593a2b4961d6b9c20',
 'providers/compiler-fixtures/planned-kmp-cases/expected_lateinit_property/negative/common.kt': '436aba16e62a93b5ad7bbdaae08e11f2ecea924b2f1858f33fc8722d4627efb3',
 'providers/compiler-fixtures/planned-kmp-cases/expected_lateinit_property/negative/jvm.kt': '07c0077424a323e09686f187391676be2b1f34cfbf5440dc6e2e9c9e378362cd',
 'providers/compiler-fixtures/planned-kmp-cases/expected_lateinit_property/positive/common.kt': '8881059de9bdca45933135a94c189c36008bd1939ec4dab738f792d1686dd235',
 'providers/compiler-fixtures/planned-kmp-cases/expected_lateinit_property/positive/jvm.kt': '07c0077424a323e09686f187391676be2b1f34cfbf5440dc6e2e9c9e378362cd',
 'providers/compiler-fixtures/planned-kmp-cases/expected_private_declaration/negative/common.kt': 'ae3502679d4949879964d8dfec40236f6658680f02778372dd158c0462e99197',
 'providers/compiler-fixtures/planned-kmp-cases/expected_private_declaration/negative/jvm.kt': 'dec74cfaea4d1ef59d3da4c007fadaa93717d2a21cc0f14a96f1242c79700c67',
 'providers/compiler-fixtures/planned-kmp-cases/expected_private_declaration/positive/common.kt': 'bb22a7ff863861b3ad1ed042c5c5e7e1abdc04b4fe168aef707c54ac963565ca',
 'providers/compiler-fixtures/planned-kmp-cases/expected_private_declaration/positive/jvm.kt': 'dec74cfaea4d1ef59d3da4c007fadaa93717d2a21cc0f14a96f1242c79700c67',
 'providers/compiler-fixtures/planned-kmp-cases/expected_property_initializer/negative/common.kt': '9eef0d106ff629208b84eec24c3ea58c12a8a60c44970c66ae2924d7d412be16',
 'providers/compiler-fixtures/planned-kmp-cases/expected_property_initializer/negative/jvm.kt': '7236143c8ecf75778a7ee26fdfb39e980fcc745f2998cfa09092cfd877a96f5a',
 'providers/compiler-fixtures/planned-kmp-cases/expected_property_initializer/positive/common.kt': 'e07183f73a2a86f9732e8c6a08cd9118950ee5e39d3acbd0c045c71cfa8368a2',
 'providers/compiler-fixtures/planned-kmp-cases/expected_property_initializer/positive/jvm.kt': '7236143c8ecf75778a7ee26fdfb39e980fcc745f2998cfa09092cfd877a96f5a',
 'providers/compiler-fixtures/planned-kmp-cases/implementation_by_delegation_in_expect_class/negative/common.kt': '7017919ad679cee53ecb4470fa43f75c510cde99260b903e581f4179cc4c89c8',
 'providers/compiler-fixtures/planned-kmp-cases/implementation_by_delegation_in_expect_class/negative/jvm.kt': '185c45bd7ee96c625eb4f704357f97de73970d2e5f1feecc1224734d0fdcca4f',
 'providers/compiler-fixtures/planned-kmp-cases/implementation_by_delegation_in_expect_class/positive/common.kt': 'c6d73212b5b80104c8ba9abab922180c389524d12cfd31098f5beb5dc07e08e2',
 'providers/compiler-fixtures/planned-kmp-cases/implementation_by_delegation_in_expect_class/positive/jvm.kt': '185c45bd7ee96c625eb4f704357f97de73970d2e5f1feecc1224734d0fdcca4f',
 'providers/compiler-fixtures/planned-kmp-cases/no_actual_for_expect/negative/common.kt': 'fb8952a67548a9adae4023008c3e7d2381d9ca277675d2e54baebb3f13531043',
 'providers/compiler-fixtures/planned-kmp-cases/no_actual_for_expect/negative/jvm.kt': '651d92336da75d7101d643a35e11242630f3260b6ca18dc58e7d87d88d58f987',
 'providers/compiler-fixtures/planned-kmp-cases/no_actual_for_expect/positive/common.kt': 'fb8952a67548a9adae4023008c3e7d2381d9ca277675d2e54baebb3f13531043',
 'providers/compiler-fixtures/planned-kmp-cases/no_actual_for_expect/positive/jvm.kt': 'cb53917dfbdec05f5accc060070018a88b46f3764c5309147133e2208bd60d6a',
 'providers/compiler-fixtures/planned-kmp-cases/supertype_initialized_in_expected_class/negative/common.kt': '64a5c2c300ca71052e79b6a86bdf593ce1d6ec42fe24eb9c6b2fa49c4de55363',
 'providers/compiler-fixtures/planned-kmp-cases/supertype_initialized_in_expected_class/negative/jvm.kt': 'eaac8f47c8d8b618ff0965f00f349616f5abf6b6ba9d1d5d02140c6ca295bfed',
 'providers/compiler-fixtures/planned-kmp-cases/supertype_initialized_in_expected_class/positive/common.kt': '5c0068d3c95577c45c24c68070ebf6a54fd7729d643fe01ea706f9cd89bf7ab3',
 'providers/compiler-fixtures/planned-kmp-cases/supertype_initialized_in_expected_class/positive/jvm.kt': 'eaac8f47c8d8b618ff0965f00f349616f5abf6b6ba9d1d5d02140c6ca295bfed',
 'providers/compiler-fixtures/planned-kmp-manifest.json': '804c242c62707457ff143d16fb03bcd1bddd2ca84b3f9aef2085dc5af800b128'}
# Affirmative445 source pins; helper itself is externally packet-pinned to avoid a self-hash cycle.
CURRENT_SOURCE_PINS = {'data/rules-core.json': '28860cb987390ff1f604488aba023fab08b614f497155b71a9760f326c009694',
 'data/rules-ecosystem.json': '261fb5c1fadbed7b9ae3b7768cba19e9716ba5067c4932e671183d13513fd2ac',
 'data/rules-libraries.json': '3e671d030bb1734242c4f51c2de8723c11abb002b9c8a743176e7cc957592c9e',
 'data/rules-platform.json': '0bfffdd0749353cb5b9b41485e29456037a5c669e232c6915fa5ca91aa6127af',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/CompilerDiagnosticParser.kt': '48fa09fc3f57241160b1d6d964c4a41f01edead05354afbcc5f2cde9f62a372d',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/CompilerFlags.kt': '750ca4544a2b801bb6020838410ec92e7278a1a6a2ca4f10e58cd17b10819271',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/CompilerPhaseTimings.kt': '7716e2e472debfb4c52bae6b9c8a28856d1e04b8ad8ce9304f479139fc0c89a9',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/CompilerRuleRegistry.kt': '02cae63a16b07c5682a32772e7867648ad4cdf01dc2f958024da620c7f1418e8',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/CompilerSnapshot.kt': 'd089b942352d99fa4f0920338669d0fbc35bbc4112fc7d032f7e8ff0684015b7',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/CompilerVerification.kt': 'a7930f91d8c3031bd39b5fab47c826db29ccd846189912b5e5f2d51d88196bd9',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/JvmCompilerModelOrigin.kt': 'ab6bc829611cbe18b5f87149acededac2f4b1e5e3506c6fe09d874886ff6272f',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/KotlinJvmCompilerProvider.kt': '8bfcb578f6a23203619fdc5f4257eb46580b39b65c9ae553b50e3f0a9d7dcb16',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/KotlinJvmInstallationView.kt': '9d9e12dfe7315a9e672d8d2d8a49979e044945c31cd73653155f320aafbf7738',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/KotlinJvmModelBinding.kt': '193952ea5d7a196490d0216747da3ced5ed4dac5376275943dfcbf17742b9f64',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/KotlinJvmRuntimeImage.kt': 'b2f63b10401c42e94376aeac4998933d743d6b60346442677c5490a07a626aea',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/live/CompilerApplicationAuthority.kt': 'aa06bcf556e133db854f121c44d3a405059dfff8fb26db9ce9c0ef3f3d183671',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/live/CompilerApplicationLifecycle.kt': 'f1d713019fbaaa78258dd0802b7c2c1b007e9bcc36be0df6a7d89c49fcd5acc7',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/live/KotlinJvmCompilerApplication.kt': '9e7a949b4d9a0b22ca3b95c3467cb8d29410c4c50dc33a76540dcb79b0059c18',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/live/KotlinJvmLiveCompilerProvider.kt': 'b09bfde3969cd9ab5c6386c472bad2bdf2472dfd752b9253af9261ed8eb9baca',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/live/model/JvmProducedDeclaredArchive.kt': 'c8160489733faa22bd3d08e380cdb2ecb70abdee945a1f40c0f4a638ff914b05',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/live/model/JvmProducedOutputPreview.kt': '71bbd55dc444c8491dc61b1a99edd67812e2cd20b9e96d9ce92f60cf0360312b',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/live/model/KotlinJvmModelCompilerApplication.kt': '94414a98329a39b27897039b91a04c5687a2182529add8bab62dbac471ad3988',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/live/model/KotlinJvmModelLiveCompilerProvider.kt': '2af16909f0936df89b426147e3fbf20d47f6ed5acfdc877eea2eccc7b9e5f6be',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/live/model/ModelApplicationAuthority.kt': 'bdf38f3b76364708d69281b1c052126c0169ef93fec73ffe1ae072218f241c72',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/live/model/ModelLiveInputs.kt': 'abf03f03e6d61b8a6ee3568a1b27ca753d1917b514d8d152bf55ab4cd9bda1e0',
 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/live/model/ModelMaterialization.kt': '28281cdc4e1af7c54413c53441672b454d3bb7f9469c612db1267ac61739e6f3',
 'modules/provider-compiler/src/main/resources/compiler-rule-map.json': 'f8566bded002ebeb09a047924036770dbefaa687950355cb08d7174e5f82a2de',
 'modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerCheckerActivationTest.kt': '84612259c31f03bbb8e85bd9606e7b0f3f5a1874fb77ec4a1c3629c7bfb29039',
 'modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerFixtureControls.kt': 'bde583248f23979fee1c78d78b77f976dad9a302bb2c8f3e33185e7067ff8be0',
 'modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerFixtureControlsTest.kt': 'c7ce230f4637046b7333c42022763642c3389faf3bd3dd1614a4931f5874f34e',
 'modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerFixtureDiagnosticBreadthTest.kt': 'eab1efe0805a2644f1c8ce8b9a67b56cb974f40da5231ad3696ac580abb9ee20',
 'modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerFixtureEvidence.kt': '2452a1e1e4303a90366a06911fdedf4ad629fdff014756f45f763888a8ebbb46',
 'modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerFixtureMappingContract.kt': 'db7d522a88ce7e5e557376623d7304c6e68ea0055ed6c70cc0afadb8944f58be',
 'modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerFixtureNullability.kt': '7ba767b7fe660979f1b5cd6ec81bd7295eec34f0e15197a578e636b87cfb8352',
 'modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerFixtureNullabilityTest.kt': 'fb882dd2c1dcae26d8770e1f805739de28b25edf7da32ac30e076d0801e3b298',
 'modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerFixtureProvenance.kt': '9b3602227e4275e514aa9cae2335de4d471cc657a783fb1a890a9569f236d72d',
 'modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerFixtureRunner.kt': '968429a1a48a5cb04c5a12aec55f10f48ff9a1d4b6655408748cd005f8e68770',
 'modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerFixtureScratch.kt': '76cf95545d5023ada15a7ec8b8bd2e55bae8a5759f2bf5bb8908a00d4a59993e',
 'modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerFixtureSupplemental.kt': 'e6104020de52316072d2e3491ca20296b5381e971a073acda69fc8ce6edd1379',
 'modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerFixtureSupplementalTest.kt': 'd1bc76856da36be79a385f260214ddb75f3ee0060006971ba36a76c1a27c5955',
 'modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerFixtureTimingProbe.kt': '2f89dd7fe83eb2189c310d7a6f972b2f2a9f409e3c6d3c4cb7963d7077ef43cf',
 'modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerFixtureTimingProbeTest.kt': 'cdc3c99660e318e06d4a5f435057f6353d35a3cad5346dbd1780ffb69ea0eaa4',
 'modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerLegacy443SourceData.kt': '57c0633410bdd2285fe92ed966124e7f76706095524a0e20eb754c8d456610ff',
 'modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerMappingContractTest.kt': 'a94347c5f5c6fed165447b915a343654a151249ebabf789ab5b2960af6d42e50',
 'modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerProviderTest.kt': '7aa3e5c156ebd529a4fe227fa83306140026a4ffec34dc4fb9f7638789e0f515',
 'modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerTwoDiagnosticMappingCandidateTest.kt': 'a3c671541daca71279ddffe10650694ff20a6f2cde682e27ba479f7e0cd7ef02',
 'providers/compiler-fixtures/authored-input-inventory.json': 'b8c6af0b0f1d68578ad9a209e900c83668fc25f67bfd7fbf272fc28720511bad',
 'providers/compiler-fixtures/cases/abstract_class_member_not_implemented/negative.kt': 'd6eeb32afa4c44f8dcb1fbfa17c3d7ff8e65f73555ad66d872c35cd0eafbae20',
 'providers/compiler-fixtures/cases/abstract_class_member_not_implemented/positive.kt': '79534a425ffc16ef5ca5e1cea258e33402a4cb8e9ac21d9082a8c9cdcc96eb1f',
 'providers/compiler-fixtures/cases/abstract_member_not_implemented/negative.kt': 'd2f5117a62a36d93a46f48e76edea42cd237fe152be92407cb54165ac9c7ce42',
 'providers/compiler-fixtures/cases/abstract_member_not_implemented/positive.kt': '35eee0d9cf896855e70941e30214a2b2d70aa679e05ba0a5fcb1793eb04e9827',
 'providers/compiler-fixtures/cases/argument_passed_twice/negative.kt': '37051bf8322e7c636d451de85296d640d79121bb4fb45f0bcfc24674d1b397cc',
 'providers/compiler-fixtures/cases/argument_passed_twice/positive.kt': 'ea61635a65c3ff235a0bd1e8f8e2e78d4aa9bc9a6c861ffcac1a83a6d0d80470',
 'providers/compiler-fixtures/cases/argument_type_mismatch/negative.kt': '84ddc70e56de0a4da5dbc3c603d8978a57c146d1acfaf98b6ee3a136c5dd4c71',
 'providers/compiler-fixtures/cases/argument_type_mismatch/positive.kt': 'e3ca48f35c28d63c5df1a72ce786fefd02da2cb052cd104f5859951f4a76b0f7',
 'providers/compiler-fixtures/cases/assignment_type_mismatch/negative.kt': '0e2a8e5c13bd454f0f33f0b54c3cee6f19180852e89fbc8d2c4dcc51f0d12399',
 'providers/compiler-fixtures/cases/assignment_type_mismatch/positive.kt': '67b5b7ba4da08a6349637de33f955c029067001675c138e4571280df3e03d048',
 'providers/compiler-fixtures/cases/cannot_check_for_erased/applicability.kt': 'eae215a94605de750be76cd9dc911368ab5ab798123768651272948de460f044',
 'providers/compiler-fixtures/cases/cannot_check_for_erased/boundary.kt': 'cd63f250360d9bec939380d308169887d7017f562699a95e49f95c455215a6b8',
 'providers/compiler-fixtures/cases/cannot_check_for_erased/negative.kt': '963d057e188f6159b9af202333d67b84af907ac2caafbec5974600f1202b42f9',
 'providers/compiler-fixtures/cases/cannot_check_for_erased/positive.kt': '281ccfd6975f676b8b394610865f5b3ebc3be01f0366bcadc1772058772c8289',
 'providers/compiler-fixtures/cases/cannot_check_for_erased/suppression.kt': 'afa0a7509ae539b0b6c4dccae4977f52ff2d893b8a8a848548c7d9bec9aac087',
 'providers/compiler-fixtures/cases/cannot_weaken_access_privilege/negative.kt': '1aa15b6a55ad7faa2f3e7adaf3cc744516a1b09eaa3e7923b28271f288a1c8f3',
 'providers/compiler-fixtures/cases/cannot_weaken_access_privilege/positive.kt': '23c8dee72795dc31e20f3ca7edb1496cad49185e61042e1777fbb3ce615cdd7e',
 'providers/compiler-fixtures/cases/cast_never_succeeds/negative.kt': 'eec391d9fcce29e433f582ce9fdfd14bdbafac4c0b9ed5ad4eb3a1ff719f3a68',
 'providers/compiler-fixtures/cases/cast_never_succeeds/positive.kt': '4f48a28b4a370f9b35415b8fe64cca67bbfafa5df56704e2b80d3ef5399c0138',
 'providers/compiler-fixtures/cases/component_function_missing/negative.kt': '1328932f99a481611eb34394c00fb01274bfc184e601bd8fd22d014bcef0aa37',
 'providers/compiler-fixtures/cases/component_function_missing/positive.kt': '8696da0c1430a6d02dab5b00d0b82c0e723ac2615b943f28c798f7bfda837520',
 'providers/compiler-fixtures/cases/concurrent_hash_map_contains_operator_error/negative.kt': '385f3821ddafad60bbbd647a59628ad937e8284c0ad6cb57788bc25e83fe3989',
 'providers/compiler-fixtures/cases/concurrent_hash_map_contains_operator_error/positive.kt': '7d58fcfdc28f70b87dca63843743a17075e9be3fbfba7fdd6ef100bba987c2ff',
 'providers/compiler-fixtures/cases/condition_type_mismatch/negative.kt': 'a82b7fb9d1d1d59522a5cbf6bb0a4c999ce68818c3135ec3cfa4a1e9a953cdb5',
 'providers/compiler-fixtures/cases/condition_type_mismatch/positive.kt': '0fbd2eb6a37ea1a1d597a2fbd33457b33fdcdde4a8b3e904b73aa1913e38e28f',
 'providers/compiler-fixtures/cases/conflicting_upper_bounds/applicability.kt': 'f832b9c433d988981fb9167b8e9dab57531064dd0cbd60959fbe88262f51f1cb',
 'providers/compiler-fixtures/cases/conflicting_upper_bounds/boundary.kt': '58a7486a1f7dbdd8a72e2ea8f75e41504458925e7622bf61bc7ef2cd878d823c',
 'providers/compiler-fixtures/cases/conflicting_upper_bounds/negative.kt': '5bfa4499f2d64af40be295aa7bf1699b5ce14be59e26502c22da7fbbce1fc864',
 'providers/compiler-fixtures/cases/conflicting_upper_bounds/positive.kt': 'fc4bdd21471be6068e6cad3687ca96a8a10224bbcde45d1540a2a7b2c1f1058a',
 'providers/compiler-fixtures/cases/conflicting_upper_bounds/suppression.kt': 'a34f0128ad63764a731737ca044ed6d828fac2412359b7616510276a5b321fd0',
 'providers/compiler-fixtures/cases/cyclic_constructor_delegation_call/negative.kt': '5da8d528ed80fbc81d1068e9fa8d291c7969bec397bfa607b4e1416f7090d9a1',
 'providers/compiler-fixtures/cases/cyclic_constructor_delegation_call/positive.kt': 'b80ebedadb56de59997f66505ef94d5950e2852f4131020d6db8bf607c2dfaf5',
 'providers/compiler-fixtures/cases/delegation_by_in_jvm_record/negative.kt': '413512bb4d09ea967dc23acf9d1d9b3230b3b5be921a88778a809534891c5bcb',
 'providers/compiler-fixtures/cases/delegation_by_in_jvm_record/positive.kt': '8d055b9371b8592af32094034dbdbd4dbb4ce055d80ca8d98a3399a934c00557',
 'providers/compiler-fixtures/cases/deprecation/negative.kt': 'bd72e7cab839f26de91a9265f6a325150e0c20b736f3d16dff8080791fad54b0',
 'providers/compiler-fixtures/cases/deprecation/positive.kt': 'a2e39d83bc4b241581b5bb77f2353bc21b516ee87bfb74c51592cbbee4bd5399',
 'providers/compiler-fixtures/cases/division_by_zero/negative.kt': '768029687bcb1f822397d08592dc6dddf337c2eb1dfa1b949a4297df99c3076a',
 'providers/compiler-fixtures/cases/division_by_zero/positive.kt': '620cbd24b74cc65e49b5cb62f8132e32a03025794dc096f9647e066e6cb1bafc',
 'providers/compiler-fixtures/cases/empty_range/negative.kt': '79d3f1591aa626f44afa8cc18691dcabe9480163fa49d4d34d993e5f26bd9d78',
 'providers/compiler-fixtures/cases/empty_range/positive.kt': '823eb81e16992276d5cebd6fe5dc19bb7b314158e1af9c75df71caefe9fe76bd',
 'providers/compiler-fixtures/cases/equality_not_applicable/negative.kt': '05d044d6e4b21dc5ae2fdd23d52a00fd5514e1cc4dd8b35422fa66e3ceb5f144',
 'providers/compiler-fixtures/cases/equality_not_applicable/positive.kt': '0f96270187449d9bdb591e341c4dac54b435854473bf3d4bbd9eee538453bb11',
 'providers/compiler-fixtures/cases/exposed_function_return_type/negative.kt': 'f593184550060456f13aad945e690e8b35a5d35690aafb64b3b3cb781415ce47',
 'providers/compiler-fixtures/cases/exposed_function_return_type/positive.kt': '8963fb30a263ae6a268ed19743d7d7c8706781eebc15b62c1c381f963bd22d1e',
 'providers/compiler-fixtures/cases/exposed_parameter_type/negative.kt': 'ec5fb5304bcee052dbfe5ac7e0d6dc7ff2350dcd1579e1fce61983d245b003c2',
 'providers/compiler-fixtures/cases/exposed_parameter_type/positive.kt': 'c10128a902801f7cc84d909d2c909995a705e8535dd744db6cf900838e2f2ced',
 'providers/compiler-fixtures/cases/exposed_property_type/negative.kt': '234668fba3b2b92e655c80764fc400b9237d372513c9296f6068744f11f28472',
 'providers/compiler-fixtures/cases/exposed_property_type/positive.kt': '2ff0ba2456a384e068b2f7dd379bb985d6dd0ba34e12bf8d5a02187bc270a356',
 'providers/compiler-fixtures/cases/exposed_receiver_type/negative.kt': 'b7151bfcf975d22b312211a4c58c5d0cd55113844acb777fbdc5ca03e8a89aca',
 'providers/compiler-fixtures/cases/exposed_receiver_type/positive.kt': 'aeac5a238ebd076d5b07fb962f0550fd7864f2c8b48cd39b2852f6ce574b4bcb',
 'providers/compiler-fixtures/cases/exposed_super_class/negative.kt': '59f0b8b568adbe86a2ce7e4fa966622115ba13903bf81a3d6f85a41cd767aeb7',
 'providers/compiler-fixtures/cases/exposed_super_class/positive.kt': '11c39788ac5ed155db3d0af7be41a0c1bc4d0dc342b1a0e3aee6ce07b766055c',
 'providers/compiler-fixtures/cases/exposed_type_parameter_bound/negative.kt': 'e50250354473d1661b555e294911d4625b20e7494a80af6b2e83d3081f21f26f',
 'providers/compiler-fixtures/cases/exposed_type_parameter_bound/positive.kt': '23836bc80da256eb7052edeccd753d6fe4b318de407b81bbe7ee46e96099a15a',
 'providers/compiler-fixtures/cases/exposed_typealias_expanded_type/negative.kt': '7938eb0475bd145e09940a7a7782065b6df6ccf4675880407090ebd3678900d8',
 'providers/compiler-fixtures/cases/exposed_typealias_expanded_type/positive.kt': '235206623fafe122f87c84e01546df23b1a30e79bd36e79904fcf285a0a10d81',
 'providers/compiler-fixtures/cases/external_declaration_cannot_have_body/negative.kt': '909b551935117bb94e0432fca13903d5bb254f2775cc9c70f4bcf108cd6f5650',
 'providers/compiler-fixtures/cases/external_declaration_cannot_have_body/positive.kt': '8b64781a0667b325d7e715efdb3845e4210ff8bd15e974bc65b6b3c02c441669',
 'providers/compiler-fixtures/cases/external_declaration_in_interface/negative.kt': '8f23ebd059999667971de15b7facd71b980b8110acd6a8f4d9fcb06f1a5f5ff8',
 'providers/compiler-fixtures/cases/external_declaration_in_interface/positive.kt': '0d71bafa380fd1455f460969d23f0497d3de364e9e9f3044a6ac1b8f3e2512e5',
 'providers/compiler-fixtures/cases/field_in_jvm_record/negative.kt': 'decca913e3c2f1f3b4ac213fc74106be3df3a202e264a7e2d55e1780200600eb',
 'providers/compiler-fixtures/cases/field_in_jvm_record/positive.kt': '66b92c8378a9df8fd02153d7e69991736b2925be8debdbf656baaaacc1fc5a22',
 'providers/compiler-fixtures/cases/illegal_jvm_name/negative.kt': '37062636e6ecaf47a99e6a784923e4935008c2cbaa52fa1cb29b0b42bb1f0d56',
 'providers/compiler-fixtures/cases/illegal_jvm_name/positive.kt': '8f17b34b8bb1662a9cc7ab76bc381ab1d703421556b3a25d1805a9801a39b04b',
 'providers/compiler-fixtures/cases/inapplicable_jvm_field/negative.kt': 'f5fe1ea129f8033c79feb7440f41bf11d0b6ce8b64be34615b58932f3cf53e0d',
 'providers/compiler-fixtures/cases/inapplicable_jvm_field/positive.kt': 'f2da19a17c53a85c2ae3bbfe65d0d6e3b8322a344e698f7ea9cdf99e807d442f',
 'providers/compiler-fixtures/cases/inapplicable_jvm_name/negative.kt': 'b530c9e63d72d110f7c26a903d686f2da3974a2f883e8871496f2456a07fd4e3',
 'providers/compiler-fixtures/cases/inapplicable_jvm_name/positive.kt': '993e7d525e6060938c660939d1f337ba71cbd47983517dee2a18f41062f206ca',
 'providers/compiler-fixtures/cases/initializer_type_mismatch/negative.kt': '08997eea65a80881d420c90bf9d806bf242475791103c6e1fbaf1d0885533c44',
 'providers/compiler-fixtures/cases/initializer_type_mismatch/positive.kt': '09b2517fe550b84a8e18b33f99941e866fd0e76bd7bf757ea319fda4cca4f155',
 'providers/compiler-fixtures/cases/inline_property_with_backing_field/negative.kt': '316ed16e8c301b5d09cf35dbf0c2522112fe29a9eefc87f0a5c8315d5be7068a',
 'providers/compiler-fixtures/cases/inline_property_with_backing_field/positive.kt': '50efe6c65275e38567692c9397648730616c1bf397f224f44400fc3a3bf85570',
 'providers/compiler-fixtures/cases/int_literal_out_of_range/applicability.kt': '6e338567d56a6a600e208ebca03903c1854ca6dbba5d1c2e3ebe4f2f176e346c',
 'providers/compiler-fixtures/cases/int_literal_out_of_range/boundary.kt': '5317b90e45898a253afe0d5a9f58d6862e8264da8ce4dd033732a1a92f4a50ed',
 'providers/compiler-fixtures/cases/int_literal_out_of_range/negative.kt': '0652fc45d72342b7d22de60ec4f14d42f65f807556a3de42f00b9a87b6361f75',
 'providers/compiler-fixtures/cases/int_literal_out_of_range/positive.kt': 'efe8e9a9f74b50cc5dd7645659b7eed8e3928311bd350a4fc2f9d6f8e438a9c0',
 'providers/compiler-fixtures/cases/int_literal_out_of_range/suppression.kt': 'c8a323d1910f22dbd1d00b3598a6f8da2f47fe9adca9cb71de82e7429eb7dce7',
 'providers/compiler-fixtures/cases/invisible_reference/negative.kt': 'f9ffd0c5efcc6660dee7d888ce26ad0f421191509397c37c0fcae2761dd74080',
 'providers/compiler-fixtures/cases/invisible_reference/positive.kt': 'c2d57bbf3e09f626c1028030b1de7df442d09b33eb035309ebcecc6a3fa52aee',
 'providers/compiler-fixtures/cases/invisible_setter/negative.kt': 'dd06f082ca1425851359d26defa7a0d4e53b7c36744217bf0a0116165bc48cf3',
 'providers/compiler-fixtures/cases/invisible_setter/positive.kt': '1473d75d9bf2f859a5633c90e584984a00eec3b034c50fa3136f9a7a4e9711f1',
 'providers/compiler-fixtures/cases/iterator_missing/negative.kt': '642af9dc71fa4658c8b59a8b50a4e0dcdd10e4f6d7da682a789764ffb0fba3aa',
 'providers/compiler-fixtures/cases/iterator_missing/positive.kt': '5e663fba752b63fe14a602bc5daac3570c51848fbcec080f9dc44ee3e03bc846',
 'providers/compiler-fixtures/cases/jvm_inline_without_value_class/negative.kt': '25a249a86e91aba07c9b9a69f6441e2eaa12207d1aa68585d464c784d4364f94',
 'providers/compiler-fixtures/cases/jvm_inline_without_value_class/positive.kt': '95a5b2916882ac920002f5cc6e5e813cfe7cccd4513a385b21deccc9fc4779ce',
 'providers/compiler-fixtures/cases/jvm_record_extends_class/negative.kt': '241163668a2438c3701702977e153aaf355fd2f9db06bc0a4b70e30878165854',
 'providers/compiler-fixtures/cases/jvm_record_extends_class/positive.kt': '161b3c756294de45a5a0c1a45e14cedc8ca15a753987b4ef6335f828dd698c7d',
 'providers/compiler-fixtures/cases/jvm_record_not_val_parameter/negative.kt': '21afc3f91a6e01a9c991cb8a5bbec10e5a910b6e7455aed448c6210683bd63b5',
 'providers/compiler-fixtures/cases/jvm_record_not_val_parameter/positive.kt': '92055ea6a5f487c36a93a08fa7be1b8fd83289450e5ff7c8f0d104905f399e49',
 'providers/compiler-fixtures/cases/jvm_static_not_in_object_or_companion/negative.kt': 'b86ef61cf9806a9d0b0b6938a9d6973a57a79f1f5a0fb96ce01f739ab31e523f',
 'providers/compiler-fixtures/cases/jvm_static_not_in_object_or_companion/positive.kt': 'e7be9832a7faf93fd48fea27bed75f836145ae746c4cd0459c250549ca5f9a88',
 'providers/compiler-fixtures/cases/jvm_static_on_const_or_jvm_field/negative.kt': '31ad1f95ac2e20f7b1ec0c855c1b51e35a43211b4f7947bff674e1dbee480466',
 'providers/compiler-fixtures/cases/jvm_static_on_const_or_jvm_field/positive.kt': 'adfe1c68708396bf2cb5ef374001f323792ab28ab449c7e4b001b07028093b9d',
 'providers/compiler-fixtures/cases/named_parameter_not_found/negative.kt': '5c20a523e3eb890796ef6c3b21940fe0f16b0bbd1e64f6e9e1aedd3140df4545',
 'providers/compiler-fixtures/cases/named_parameter_not_found/positive.kt': 'd8a874bc538fcd2399712682d6d6b255c0ebedb495c8c2762a912ce567223706',
 'providers/compiler-fixtures/cases/no_else_in_when/negative.kt': '4aafd331666be6854bf57d3642bd7257d60163803b0dc3ae982691bd59dd6b5c',
 'providers/compiler-fixtures/cases/no_else_in_when/positive.kt': '5d8f5be96dcbe428c44cf261201a0a02afc48c4734e8e7850c117bfd3d14e575',
 'providers/compiler-fixtures/cases/no_get_method/negative.kt': 'ab955ae686e4bf7464398cb09b061374d4e694f3fc533e1715cb19f78f44b2a2',
 'providers/compiler-fixtures/cases/no_get_method/positive.kt': '36c4d1ca6646d98ac45c1654a8077d680371f13c73dfc69358bd8d32a63c5d05',
 'providers/compiler-fixtures/cases/no_set_method/negative.kt': '92a9cdd770def96626bee6bff0f49e7cf7a98aeb5876c2537229001c634367e4',
 'providers/compiler-fixtures/cases/no_set_method/positive.kt': '4ddbce3291b3c0b9bbb46bf71449d7658939a00a218977c86b2ca01e7a19055c',
 'providers/compiler-fixtures/cases/no_value_for_parameter/negative.kt': '168700c9d126cd38057b379840e8f4d722825e3d41676619ad3b255562db536f',
 'providers/compiler-fixtures/cases/no_value_for_parameter/positive.kt': '870e8886fa5dbdf28e79b57226ef0c303b2672f1304d8adf7f090a97c0f0634d',
 'providers/compiler-fixtures/cases/non_data_class_jvm_record/negative.kt': 'e28b0b5ccaa935d7ffe5c80d16fab36fc943211fd205b4368c4836fda322ce57',
 'providers/compiler-fixtures/cases/non_data_class_jvm_record/positive.kt': 'ca919b1c863819f48f365752a348b440fb74580576dd3bb8d43ed4dc01bcfb37',
 'providers/compiler-fixtures/cases/non_final_member_in_final_class/negative.kt': '99ffd4d2b349bb47949b845c583c0850f06ae0c17c7d78b02eccd24f8ba425aa',
 'providers/compiler-fixtures/cases/non_final_member_in_final_class/positive.kt': 'd6066a54ae1680c843fc8a0ee56f5ef2b8e734c00baea89395eea6e7ada1bb2b',
 'providers/compiler-fixtures/cases/non_public_call_from_public_inline/negative.kt': '21227edfa94aca82d8addf2b8ebf0298f13cfeadb702205bb701c19ada5acc39',
 'providers/compiler-fixtures/cases/non_public_call_from_public_inline/positive.kt': '79a173871e46fc0187b97876842eb714fd88deafaa2b75b88babfb0ca2604ab0',
 'providers/compiler-fixtures/cases/non_suspend_overridden_by_suspend/negative.kt': 'a30080c3ded18a3d9ab3e9e21e8ceca079707db1d5bbfbcae62c64fec2f6d09d',
 'providers/compiler-fixtures/cases/non_suspend_overridden_by_suspend/positive.kt': '7c50a15ac211eeeb65f0854fffdea32d7802d8401d9f3b340c3a761b7ceaf05b',
 'providers/compiler-fixtures/cases/non_vararg_spread/negative.kt': '8dac2d7a498631eeb6e3e235cf26e2a0b01387115c5690db91dec2f50a96a274',
 'providers/compiler-fixtures/cases/non_vararg_spread/positive.kt': '02dd78ac96f8906436e5cb2609f34fa2416cba1b665deac09abd6fedde4c6f90',
 'providers/compiler-fixtures/cases/nothing_to_override/negative.kt': 'f99dd8d6607f2fb1d0431540f440ca63bf07674ccb34109c53fd78bcba165bc5',
 'providers/compiler-fixtures/cases/nothing_to_override/positive.kt': 'c3c034d9fc35d2bc2705c917dd2b28951d5ad96cf1321fac574a270317e105d7',
 'providers/compiler-fixtures/cases/null_for_nonnull_type/negative.kt': '1bbfb915c432d6f14bcb4086ec22d6b1731ea2a640f39ab7f6773faca66b1fa9',
 'providers/compiler-fixtures/cases/null_for_nonnull_type/positive.kt': '960419f714930897b4c5910942d4cdedc2804949357043766e0ff14c7f508a5e',
 'providers/compiler-fixtures/cases/opt_in_argument_is_not_marker/negative.kt': 'e50ff533619e6ff00a99a6e43dc20c6f000b9fd87fbb6a4f9021f972b8a45b45',
 'providers/compiler-fixtures/cases/opt_in_argument_is_not_marker/positive.kt': '38ba5bb448403d554b756955223d43d3dd75b185ff18013938db270d0a030f36',
 'providers/compiler-fixtures/cases/opt_in_usage/negative.kt': '02f1aad6d372e718bcff248351c9d5bd2419326ee7fe42f482bccda2e713b2b1',
 'providers/compiler-fixtures/cases/opt_in_usage/positive.kt': '06fd3b9727ceed8f2520f7e689cef62ac42744a660c90f30bc15bfd9603a86cb',
 'providers/compiler-fixtures/cases/opt_in_usage_error/negative.kt': 'b4b78dbbea7eb6e1409d6d60978cd86ad17e8cc43110d10a0570ac7b48f333c3',
 'providers/compiler-fixtures/cases/opt_in_usage_error/positive.kt': '961b0490b56a48fdc2100ed7eb468acf40e484399c9900187999bcc3cb73d8d4',
 'providers/compiler-fixtures/cases/opt_in_without_arguments/negative.kt': '9a6e4034e60a1e94cc3ac038451e0dc242561f0dec33de64b2b8d8ff2a9ce243',
 'providers/compiler-fixtures/cases/opt_in_without_arguments/positive.kt': 'aaaf0943bae85de13456e717a2441ee0493ab5299f3e37b1dd92c0e21f1b0958',
 'providers/compiler-fixtures/cases/overloads_abstract/negative.kt': '4f1d0d2bc8de320edae518649f70176df7bfc8b81f9aed36eee221db6270cb81',
 'providers/compiler-fixtures/cases/overloads_abstract/positive.kt': 'f267bce2c4c7a1f302626a535208c9747476f01f6497dff5ca2fb6285d373654',
 'providers/compiler-fixtures/cases/overloads_interface/negative.kt': '5988fb3c335705efc1ed053807422f64fb2543c25c356f9aa8a7eb428a4d54c3',
 'providers/compiler-fixtures/cases/overloads_interface/positive.kt': '53ed21646366bc097838318d558128412c41861ff4e687205701d998b89ccda5',
 'providers/compiler-fixtures/cases/overloads_local/negative.kt': '5d28186003b55ff4fe19e890727e82a8bd7e8629157674d1027be373b55ced39',
 'providers/compiler-fixtures/cases/overloads_local/positive.kt': '7ce3e8de373e42c4f495e315fa98ac17c70a7c6e9a2972c4c9300a35f946519d',
 'providers/compiler-fixtures/cases/overloads_private/negative.kt': '431455b51295e161ae0b75398f5c25660cd977afafb95f98a92255ebd218c3b8',
 'providers/compiler-fixtures/cases/overloads_private/positive.kt': 'e3456298b600a0d699f475fc1a956b5d3997b5ca90bb57291fad25f895219f44',
 'providers/compiler-fixtures/cases/overloads_without_default_arguments/negative.kt': 'cc302a10c855f2dbe851317a4e38aac4b1ce9d5e32549b84a86f9ce20ffbd6d3',
 'providers/compiler-fixtures/cases/overloads_without_default_arguments/positive.kt': 'c46dd40de00400bbe0b26406c1c6b4dcaf19e757742c813feb15f72c2d53f74d',
 'providers/compiler-fixtures/cases/overriding_final_member/negative.kt': '96fdafe2fe75399f2e2fed4518c3b7270106e9ff707f6a8015490a9d95007365',
 'providers/compiler-fixtures/cases/overriding_final_member/positive.kt': 'd2cd18fc25117108dc0b91292b475922d84da990e181034642f953c35cc1ff66',
 'providers/compiler-fixtures/cases/parameter_name_changed_on_override/negative.kt': '873e41c5527ce865e11eaecc013753044895676efae61ae5468a5af9c1b229eb',
 'providers/compiler-fixtures/cases/parameter_name_changed_on_override/positive.kt': 'b51e3b7426bd8a217caf63340e055a72de54da6d02b505feb8eba0603055e026',
 'providers/compiler-fixtures/cases/property_type_mismatch_on_override/negative.kt': 'b2621871c6adc5fc5265d74e79fe50f976d187ddb2848f5c94b5e32e261d3bcd',
 'providers/compiler-fixtures/cases/property_type_mismatch_on_override/positive.kt': '6270fd4527663b409fd91ae1b7208fd421b5d48c64b87c45b6e1dda7dca6ced7',
 'providers/compiler-fixtures/cases/recursion_in_inline/negative.kt': '48261b52380e0b7943b973b0f113b11d037467e000fd86025911fb7ca83d545e',
 'providers/compiler-fixtures/cases/recursion_in_inline/positive.kt': 'c15fd1196e4dae01375c2ec782169159a3e788e718b983f49844c94f8f7dccfc',
 'providers/compiler-fixtures/cases/recursive_typealias_expansion/applicability.kt': '78ef670e4512bbcad9866de3d57e3a416888fe9b7cd738511e228c380b7d6140',
 'providers/compiler-fixtures/cases/recursive_typealias_expansion/boundary.kt': '27546e583f4ff54393af9d0ceaaf8e7d1806c884bd03af1489180bf8bad1d19b',
 'providers/compiler-fixtures/cases/recursive_typealias_expansion/negative.kt': '8dac0869823da179a27e8c852fb2c07df7f1394301a466cd53092a3771c653fb',
 'providers/compiler-fixtures/cases/recursive_typealias_expansion/positive.kt': 'be045c201b98296f8b9c09e60a2c3c7e737a26342ef93afcac2e120edf2e3593',
 'providers/compiler-fixtures/cases/recursive_typealias_expansion/suppression.kt': '8e089e9e6464df1fe29d6124f56289d057f0060e9bd35536c42d162b1bc40e1f',
 'providers/compiler-fixtures/cases/return_type_mismatch/negative.kt': '0193c131a5bb90f961699f6da6c2803a41a7b7c0986471468838da0a425bee1d',
 'providers/compiler-fixtures/cases/return_type_mismatch/positive.kt': 'c986ad1553c671aae2f601948702d049951977d9249a240493d7fda39f3ad88e',
 'providers/compiler-fixtures/cases/return_type_mismatch_on_override/negative.kt': 'fd576f80387a5b12ef96b923974aca2584e4788674301027c4d362a7a0bf8258',
 'providers/compiler-fixtures/cases/return_type_mismatch_on_override/positive.kt': 'ff8b134dc63f1d8b7170ce127a8136e6d1971210b58b527477fadf0ee8742f88',
 'providers/compiler-fixtures/cases/smartcast_impossible/negative.kt': 'd286b894b93c8c307e2cc3ba26584c30322ac62817f8f9ed7cde511fc0458dc4',
 'providers/compiler-fixtures/cases/smartcast_impossible/positive.kt': '85b8c106b40ec2c8c0989f71d4145ee38e6c4c1eb86d7ea17412752dc169c2c5',
 'providers/compiler-fixtures/cases/spread_of_nullable/negative.kt': '54133866b669f4c598a416a2085af7104e0005ba0b2418e94eccc5dbd3d10db0',
 'providers/compiler-fixtures/cases/spread_of_nullable/positive.kt': 'fbb0192f4de9a69c5229ada9655571d9d4c143aeb48e6b5d6873b9a9c1c44d4d',
 'providers/compiler-fixtures/cases/supertype_not_initialized/negative.kt': '1af2c7a56767acb5fff59826c456716e909197589b59062cf334b727ec85ee13',
 'providers/compiler-fixtures/cases/supertype_not_initialized/positive.kt': '62107df1f76f991026287bef42b7482fcf1c1943000716facddf3ee8cc876683',
 'providers/compiler-fixtures/cases/suspend_overridden_by_non_suspend/negative.kt': '6d7d0cca71beefd93935049b296a50ba4a5833a3ed8b2d783168fe2d80424f15',
 'providers/compiler-fixtures/cases/suspend_overridden_by_non_suspend/positive.kt': '5bb7d7a6b9c75aa8f35f3ef6ffb8a08f5771790ec9f6567043a7aedd480839e8',
 'providers/compiler-fixtures/cases/synchronized_on_abstract/negative.kt': '2dffa2b068a6f44545603aa4cf8246779e11c97424b0302e9f1a26bcccb64d33',
 'providers/compiler-fixtures/cases/synchronized_on_abstract/positive.kt': 'faa782995219cd8213520c430ed99ab2130b4102fe5bc1f11388663afbca6145',
 'providers/compiler-fixtures/cases/synchronized_on_inline/negative.kt': '8e69a7864553d650fcc43b3f98dd43dacda874d8bba77a57997f6c63fc12a81f',
 'providers/compiler-fixtures/cases/synchronized_on_inline/positive.kt': 'deed6c4c156441afcd5541bf99fe17e89f5691d5248bc974a67841c47457f8c5',
 'providers/compiler-fixtures/cases/synchronized_on_suspend_error/negative.kt': '08f28afe892afd4f8c23ea8290721d41ea847afd542ad8bbb46baed3aeea20fd',
 'providers/compiler-fixtures/cases/synchronized_on_suspend_error/positive.kt': '38cf0b428a803a6d92cf436b45e45b21104a460fb6ab1e3c33d8b7e5b6a22d3d',
 'providers/compiler-fixtures/cases/throwable_type_mismatch/negative.kt': 'c61d63da59e56f7300bd98874b17f4bb2d257691e9dcbc5944f8b3a0308ac88f',
 'providers/compiler-fixtures/cases/throwable_type_mismatch/positive.kt': '67e0af1219e84ed020aaf85c928e7e0c30c3b211eda29dfb0dae7685eedbd72d',
 'providers/compiler-fixtures/cases/too_many_arguments/negative.kt': 'd07b0b6ce2d154e827627cd6ce7b2f0ad2a8c750712e189875f1edd3f875ab5b',
 'providers/compiler-fixtures/cases/too_many_arguments/positive.kt': '94f3450e4cccca25c787bd9f2f34518d4dd9bb03e9974d372e122005be9ed7a3',
 'providers/compiler-fixtures/cases/type_variance_conflict_error/applicability.kt': 'b964d3824925310f5c878cef028d4f6fcece9e838f42962d25b91e59db9c9f1f',
 'providers/compiler-fixtures/cases/type_variance_conflict_error/boundary.kt': 'e84683626556459de8661d278d632556b49a798f54863b088470aaa037b998b6',
 'providers/compiler-fixtures/cases/type_variance_conflict_error/negative.kt': '0b668d62c4e2561900625e51b880a13ca166824993979ffe525855a7feac17ab',
 'providers/compiler-fixtures/cases/type_variance_conflict_error/positive.kt': 'a5b2acf2fc4edf6aa75e4a1efbb4cc8a25a521e6e50d1ed7029a4f180a23519e',
 'providers/compiler-fixtures/cases/type_variance_conflict_error/suppression.kt': 'a72f4f74a5492cd76ed5d8095169c417371bde406902a373929b9b035f4d73e9',
 'providers/compiler-fixtures/cases/unchecked_cast/negative.kt': '9344cad147bb3d029a687385d7156d6da0e74947a272ce9d302925062e430c44',
 'providers/compiler-fixtures/cases/unchecked_cast/positive.kt': '72f1209b89c0a082f28c63eb8a89b391fb20e8e7cbd61da5ad48688cbd6e7160',
 'providers/compiler-fixtures/cases/uninitialized_variable/negative.kt': 'a38a03ced2e492e0d67299b1ab4b12ff674435bf080b2dee38d4d67f8ff544e2',
 'providers/compiler-fixtures/cases/uninitialized_variable/positive.kt': '50b19de9b95ef105643f1515b5d12b5b7b6074329a982e913378533e3c946465',
 'providers/compiler-fixtures/cases/unnecessary_not_null_assertion/negative.kt': '3c9dbcee9ae1e837ef55c6286d76490ab04384b3f4a945fe681650429aa88e4b',
 'providers/compiler-fixtures/cases/unnecessary_not_null_assertion/positive.kt': '8f38ac71ea8f36cf3733fb2257e6dab26383b212ee7a86171e0dd5ca67ad3ea2',
 'providers/compiler-fixtures/cases/unnecessary_safe_call/negative.kt': '809ccd608c17ae112809fd952f506cf1166196b008acd1189277b9b20af5b159',
 'providers/compiler-fixtures/cases/unnecessary_safe_call/positive.kt': 'ccc966cea89a4f9041b23f81c5fad758de0d54012f7998396b8399b5874bb548',
 'providers/compiler-fixtures/cases/unreachable_code/negative.kt': 'c116121d5e27e7f351736fb701838c0ab3f793c25d81e8be4ddadb11c8b95af4',
 'providers/compiler-fixtures/cases/unreachable_code/positive.kt': '4c3c72e1d68b9e2c168c2a029a3aaf04f707e5753f5a80cc1932134c03dc1c8f',
 'providers/compiler-fixtures/cases/unresolved_import/negative.kt': '312ef33f114811ca282e51a44c617bcdc91838f79e1d86731355f2bc57c7a919',
 'providers/compiler-fixtures/cases/unresolved_import/positive.kt': 'ed407fd6fcb4501abac71543be3b3dbb5eb3d69c3016e900c6e52157d22dfaf7',
 'providers/compiler-fixtures/cases/unresolved_reference/negative.kt': 'b806e34702c37d638d0a6452ff9b6c90484a256f6e285ca51a08ae33b5f0970e',
 'providers/compiler-fixtures/cases/unresolved_reference/positive.kt': 'cc58d834595d3f05f4dad1f3a568ea60561c63a08974847a5024d627cc3e940a',
 'providers/compiler-fixtures/cases/unresolved_reference_wrong_receiver/applicability.kt': 'fad5f520c0fa0cc9e72b3cf3a88c5ee51eef3f44a6a263a4aeb1de1dc123c2b5',
 'providers/compiler-fixtures/cases/unresolved_reference_wrong_receiver/boundary.kt': '05f4412ed537aefc23824dc5223a8f09ed7c1474944411bfc884c895af284c0f',
 'providers/compiler-fixtures/cases/unresolved_reference_wrong_receiver/negative.kt': 'd0e173375718e257dbc8a269ace8a1f0f8b61d97905c482534f4f083f4dd551d',
 'providers/compiler-fixtures/cases/unresolved_reference_wrong_receiver/positive.kt': '53aeeeb8bc32c49c8f24a0baa483182570093924dab33689a9424e2533409dfb',
 'providers/compiler-fixtures/cases/unresolved_reference_wrong_receiver/suppression.kt': 'c5de166c4603fef3470e978bf72a436885a11b05fbf4227291388b5026722dcc',
 'providers/compiler-fixtures/cases/unsafe_call/negative.kt': 'f2a9d838a60636e6383e842c6f819fe32e9ecd60fee6b2d47215f0a02d66b9ca',
 'providers/compiler-fixtures/cases/unsafe_call/positive.kt': '4f903026cb19f77c8a7e8fa600cd8bbc75609f9aeb03d7bbe6297bbac5d193f0',
 'providers/compiler-fixtures/cases/upper_bound_violated/negative.kt': 'd48e64516ae15b1c4fb1fca18bcca2b2e2992fe8b300b80bc1793b5b10e30be3',
 'providers/compiler-fixtures/cases/upper_bound_violated/positive.kt': 'd4c523be141bce9e62df159a14cb7e31c3bc7842cedca3630dca17b2223b0f50',
 'providers/compiler-fixtures/cases/useless_cast/negative.kt': '4963806bb8acc6e0d49d6e8e711c701aae8a5fd25420a013c6de50c72dfd9d28',
 'providers/compiler-fixtures/cases/useless_cast/positive.kt': '08e2747e355aae41d2698f1b26518fb549e7405163d4d3a5d9099409040877b4',
 'providers/compiler-fixtures/cases/useless_elvis/negative.kt': 'd1e45039824ead644fbb8210895e883feb8667172da25490398b454e235a8eec',
 'providers/compiler-fixtures/cases/useless_elvis/positive.kt': 'a6ac2f1ea9e64a4a1077ada8e42a4b8b655495fc5f49abeb6d59a660c96c1de2',
 'providers/compiler-fixtures/cases/useless_is_check/negative.kt': '973eb206f6e30cdeb950eb892ebac1eecca0393c697472a116a1ffa80b7f9eb4',
 'providers/compiler-fixtures/cases/useless_is_check/positive.kt': '5f8b9f40b8453cbce06352ab4155f1193bfad26ce831afcb83b03d8eda6b2d1f',
 'providers/compiler-fixtures/cases/val_reassignment/negative.kt': '3ad282b3a83b8f21f31eb83a10aee22ad8b21a5c5b512e2694c52f85fc5d34fb',
 'providers/compiler-fixtures/cases/val_reassignment/positive.kt': '3bf39cc517c492848601e06ff06ee5f6ec0fec12b80b99309a129cb8a8a11ac3',
 'providers/compiler-fixtures/cases/value_class_without_jvm_inline_annotation/negative.kt': '066b585e95d08c5459a48bd9fc34655bae4c2eda1608c4c69df3071c31c1441c',
 'providers/compiler-fixtures/cases/value_class_without_jvm_inline_annotation/positive.kt': '1b1efa55fa59f3d9c9a0e71bfeefa00bf443dd1cedf5176a7232ea29beabe81b',
 'providers/compiler-fixtures/cases/var_overridden_by_val/negative.kt': '5886b8e269f84ef67fcf51fbf97e892436708f478aae7e6d1fd1439bea7a46fa',
 'providers/compiler-fixtures/cases/var_overridden_by_val/positive.kt': 'e2ec7d91db91e8bb5319dbd030cd0639c69b19f204d007d7df2b45f7ad478979',
 'providers/compiler-fixtures/cases/virtual_member_hidden/negative.kt': '794f48d4c3dad20b906ef0cc6eebc6ca076ec03f497832cdedce5d326ede1fbd',
 'providers/compiler-fixtures/cases/virtual_member_hidden/positive.kt': '64cc1688a01fa924e6839e1bde0b481572024825f14f64e1fa954f293a9c7572',
 'providers/compiler-fixtures/cases/wrong_annotation_target/negative.kt': '6fbe78a340fee808c5cfdfa929359a9237e370718c25bafe144707d4764a244a',
 'providers/compiler-fixtures/cases/wrong_annotation_target/positive.kt': '2b118d54a8d08ec37128a443af8394088f1b9006fbf5bde609e4d69fc801f2cc',
 'providers/compiler-fixtures/cohort-plans/diagnostic-breadth-README.md': 'f28007321273fafbff8b4562977d72b15f8607cacc5359db5162ecae0818148b',
 'providers/compiler-fixtures/cohort-plans/diagnostic-breadth-gap-inventory.json': '9cbf6743f83d25dcef58325997552539e7c0116efff3a8523ab58c87df456c54',
 'providers/compiler-fixtures/cohort-plans/diagnostic-breadth-source-evidence.json': 'ed4e9f5c81d0ca7383fe38b41da4d4a23e419b7afd5ecee1e178fa9ffc9b546b',
 'providers/compiler-fixtures/cohort-plans/diagnostic-breadth-v1.json': '4bbb0528fcd0ab9b7c5c8fb5252bbe03a481666abed1ec5bb29b6632bed8b8bd',
 'providers/compiler-fixtures/cohort-plans/diagnostic-gaps-445-cases/error_suppression/negative_resolved_annotation.kt': 'c99c7544e3c6cfd6cb4678b4362f9220be67b59ce9c6041c80626e016a994b8d',
 'providers/compiler-fixtures/cohort-plans/diagnostic-gaps-445-cases/error_suppression/negative_warning_name.kt': '6f975f5abf6fe3313ccd497b2baa9e2569a78b6074be79d076d32feb91f53755',
 'providers/compiler-fixtures/cohort-plans/diagnostic-gaps-445-cases/error_suppression/positive.kt': 'f780251fe2aa94d7ea419fc21c989229be2339c5900ecb8591713d32070f9906',
 'providers/compiler-fixtures/cohort-plans/diagnostic-gaps-445-cases/error_suppression/self_suppression.kt': '3db7d0b580109ce5b11c72f1f36501833e2f23f90a86bb836ff7343dfcc0bfcf',
 'providers/compiler-fixtures/cohort-plans/diagnostic-gaps-445-cases/inapplicable_operator_modifier/negative_boolean.kt': '1e4ece9144ab8578ab1df8f8c53b04d520421701309e62613e16f7d9dcad9352',
 'providers/compiler-fixtures/cohort-plans/diagnostic-gaps-445-cases/inapplicable_operator_modifier/negative_no_operator.kt': 'e27051bd9af60d9ea420fee80b0a03e89314ba892c2e2178c28cd1f7501e1f7d',
 'providers/compiler-fixtures/cohort-plans/diagnostic-gaps-445-cases/inapplicable_operator_modifier/positive.kt': 'acc2d15a2cffcd7007aef4c261387a5a7b04c6ab111f7a35abbc752483cd8ead',
 'providers/compiler-fixtures/cohort-plans/diagnostic-gaps-445-cases/inapplicable_operator_modifier/suppression_observation.kt': 'b0b57cd443c3eba96e13552363fb502da30ad0657c7323d5e102fcc3cb14a6b9',
 'providers/compiler-fixtures/cohort-plans/diagnostic-gaps-445-source-v1.json': 'ac16e9b94c9bfe50b6d1c1799c7c216ed907219d272e88383192dc3a7f0bea5a',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-README.md': 'a8bd4914cdc7d337add5e1eed8398ebbf32ef8d9f2f0c4ea2a182a6785d62db7',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/iterator_on_nullable/applicability.kt': '7677625ababb895156875d6f172ec555c116515a1f6ad38b90e0bab3d83220f5',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/iterator_on_nullable/boundary.kt': 'bffaee3477b8cd269a3edc8d83767173f8bae7007579a520f71fa8934e45b3a3',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/iterator_on_nullable/negative.kt': '9b4911e6de82cb703c30afc824b75fa66bbd6946504363c232e64107a62fec04',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/iterator_on_nullable/positive.kt': '3e571b48b075a567cde4d06b304159ebce2ee98c5123a33277c630498ef86163',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/iterator_on_nullable/suppression.kt': 'e82cc0ca2a434fa8cd893a10b651d02db740c6b160b263af4a9d5b26e3cd5079',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/unexpected_safe_call/applicability.kt': '5454aca7e046292a36febc10710550a262dfe661f2372ab04e164c568ecd2f7b',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/unexpected_safe_call/boundary.kt': 'eb6a0212a10caf44fdd1447f19a4f32e29134f4fdf3aae9a01f99fad7f83eadf',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/unexpected_safe_call/negative.kt': 'caac88241f68eb0ab07f681408f50155fea8bb8d8bbc282b54da9b8303a7797c',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/unexpected_safe_call/positive.kt': 'abcfbe94c77b33690b2ced052fff55c0c6a14db41b248ca241f81f09c271a3c4',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/unexpected_safe_call/suppression.kt': '33c9235a65140044479c14f55f8dc04429e0942de4af692cd441a8280ec280d2',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/unsafe_callable_reference/applicability.kt': 'e651fdc1199a6dc802b0930ee6ca8e4a76bc7e64aeb84006d9b86059e1629b2c',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/unsafe_callable_reference/boundary.kt': '76ac4763a73a7394707edcff444fcf9c6305137b064617f61591135ab526cedc',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/unsafe_callable_reference/negative.kt': 'ae6150502d1274c9298f3f8beac7bb2a1d76abd6493f72d2a61023506c654c73',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/unsafe_callable_reference/positive.kt': '5a2f487292a8f3f7eb99450a46701eb7546e74879eaafa4b24c702de3ebcac7c',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/unsafe_callable_reference/suppression.kt': '09af74f7a83980315d267ce8b663be456707b5f342214d9fe8eaa723711acc4c',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/unsafe_implicit_invoke_call/applicability.kt': '1894ba6be39d7b43f2d85970182c4d2d0839c27be4d85e8b10238815ad0cfad6',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/unsafe_implicit_invoke_call/boundary.kt': '8370b165cbf685e814ae7a02d6ca45d7e5230b349745dde556d95913c2463a51',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/unsafe_implicit_invoke_call/negative.kt': 'afe94061f0e38539f5c6af16eecf10b3e19544725d760ec09c784e310dc3cf9b',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/unsafe_implicit_invoke_call/positive.kt': '52ce436172dae231416bc02452151f569f8760cb9c0439a1bfac2b1f080ee6ea',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/unsafe_implicit_invoke_call/suppression.kt': '8558e3a92bc5f4586a9eae836cf272687617318b483f269fdf3296b1b427ebbc',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/unsafe_infix_call/applicability.kt': '7939d9a41264cb8b31219092b0f31ffee70b32f8e7a54dad791c08fbd8f95dd9',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/unsafe_infix_call/boundary.kt': '843e37f6f343c5524092c12c4e30d2f29c8209b15fded1463353904c1f1fd2f4',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/unsafe_infix_call/negative.kt': '653b41cebbda3d5eabe8405cb428ee9b328d9b573ebc4d8511297efa805ca562',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/unsafe_infix_call/positive.kt': '6285cb534f872d68c0461217d1eb9bb080eb7958bb0a3cc1b59b3bfde2e9496e',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/unsafe_infix_call/suppression.kt': 'e3f44e589b55ed9865957cb93d0aa31e6cb288ef3531ccc3531186816b89be0f',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/unsafe_operator_call/applicability.kt': 'c8cd8fe0d4fb73f2c35f613f047bcea6337a750ff06ca5fb0593c021384645bf',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/unsafe_operator_call/boundary.kt': '204d5ea963c341f84b4d8eed4b153e4996baf09149653292c74301ac896b99ea',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/unsafe_operator_call/negative.kt': '30f620138e400107d6a75fa5a5914c16e6b663f84715de7bbb2a1797d2bb2744',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/unsafe_operator_call/positive.kt': '0b4b77531c01ff2135f6d08e96d315a6e7501642e28999f01bade91a236a63ba',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-cases/unsafe_operator_call/suppression.kt': '8982dbd99ed2256c06190a631d319c8322c567cef00da01dda6634f748b60946',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-gap-inventory.json': 'dbdf33758d612786e9422d45551cdb3fda43f3f4a3fad57a7e3fab47f6c03a70',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-source-evidence.json': '2a453246ad9db2c0499c5c2c9cd488261493a786a5185618d91beafeb83ea0b5',
 'providers/compiler-fixtures/cohort-plans/nullability-protocols-v1.json': 'e8d9eb399768454c0ad648d2b2f61024e0924cc3589c6f0c3bddbadf2fe1af44',
 'providers/compiler-fixtures/cohort_expansion.py': '2dd0d0323b507da9f4ff3e758ab0951f53fcdf196a46006331e5e33681f33edb',
 'providers/compiler-fixtures/generate_fixtures.py': 'b46220dcf987229173094db7525b2e3645c345d0f81eb7814f7d37b56eb11faf',
 'providers/compiler-fixtures/generate_rule_map.py': '8b99ee41d6f9942ad742ea24e8365e17a862b7f5b9cb1167f1d50a2867bc617d',
 'providers/compiler-fixtures/manifest.json': '4854aa356caba6e90b48e10e333bf32828460dc64b04dd0d6b86db3653d49774',
 'providers/compiler-fixtures/mapping-contracts/legacy443-data/README.md': 'df3d50e873eee7e1351aae4a3b0895fa9be0ab86ef453a1be241fca566007dd3',
 'providers/compiler-fixtures/mapping-contracts/legacy443-data/manifest.json': '3d8d32a84269a411af099b5f24f4ed38c178db96881cf47accdc11094a156a15',
 'providers/compiler-fixtures/nullability_protocols.py': '6e2774bd505110cca4e07eaddacd00d5a3e0a626a7a5e7b238da1798d9840ab1',
 'providers/compiler-fixtures/planned-kmp-cases/actual_function_with_default_arguments/negative/common.kt': 'e7f1ddb197e681fff8651989e393df7a27ee16cbb2d44f62e9b9234a718ea7dd',
 'providers/compiler-fixtures/planned-kmp-cases/actual_function_with_default_arguments/negative/jvm.kt': '7adf1029d48613c6d690d60284d6eb4b041b11b03115545e3ecef5ee91c639dd',
 'providers/compiler-fixtures/planned-kmp-cases/actual_function_with_default_arguments/positive/common.kt': 'e7f1ddb197e681fff8651989e393df7a27ee16cbb2d44f62e9b9234a718ea7dd',
 'providers/compiler-fixtures/planned-kmp-cases/actual_function_with_default_arguments/positive/jvm.kt': '098428a49f2018668521d9f1c1ffd84a5f801bbfd6b8deba4101fff68ecda85a',
 'providers/compiler-fixtures/planned-kmp-cases/actual_missing/negative/common.kt': 'c1e89d7956c0bc5b96f832ae53da72f72f14bbe77d6096950f3fb94b660f0c94',
 'providers/compiler-fixtures/planned-kmp-cases/actual_missing/negative/jvm.kt': '9827156000176fcbeb1696c74af9eaa6c3737a8908723fa6b2dcf8502e20cc21',
 'providers/compiler-fixtures/planned-kmp-cases/actual_missing/positive/common.kt': 'c1e89d7956c0bc5b96f832ae53da72f72f14bbe77d6096950f3fb94b660f0c94',
 'providers/compiler-fixtures/planned-kmp-cases/actual_missing/positive/jvm.kt': '442de7a7a8d4b2ff896d5648fead923bdc4fcb4d86a80badc10516574b516536',
 'providers/compiler-fixtures/planned-kmp-cases/actual_type_alias_to_nullable_type/negative/common.kt': '8280d624a983cc84fdb0a4fb34f58a71d59ee7137b473b8cd338c0b7620580a6',
 'providers/compiler-fixtures/planned-kmp-cases/actual_type_alias_to_nullable_type/negative/jvm.kt': '7290b715996d5c6ab087c914235c9e05e0a56476263c6f2e6592b780af4b40e6',
 'providers/compiler-fixtures/planned-kmp-cases/actual_type_alias_to_nullable_type/positive/common.kt': '8280d624a983cc84fdb0a4fb34f58a71d59ee7137b473b8cd338c0b7620580a6',
 'providers/compiler-fixtures/planned-kmp-cases/actual_type_alias_to_nullable_type/positive/jvm.kt': '25f6d526f1314d9580ca10e0b9035a94541745ae6bcc636ccd69210966bd8049',
 'providers/compiler-fixtures/planned-kmp-cases/actual_without_expect/negative/common.kt': 'f8072a229d219f2b38567a293b9bdf0b46c36c264608a9a376bec1bcefdbac4a',
 'providers/compiler-fixtures/planned-kmp-cases/actual_without_expect/negative/jvm.kt': 'c0838a70c3ef17477508e13bb99aebb0ee707f0240062bc543c29550cd08e775',
 'providers/compiler-fixtures/planned-kmp-cases/actual_without_expect/positive/common.kt': 'f8072a229d219f2b38567a293b9bdf0b46c36c264608a9a376bec1bcefdbac4a',
 'providers/compiler-fixtures/planned-kmp-cases/actual_without_expect/positive/jvm.kt': '89f8c1404f66a7f3cd7f6b120522ec77721573ac976d1d19c13ebcbaf4927f8f',
 'providers/compiler-fixtures/planned-kmp-cases/expect_actual_incompatible_parameter_names/negative/common.kt': '9c091f7e1ce76cf587c919d33f8e1826b63af8e6b341d0766670e4acb1a539d7',
 'providers/compiler-fixtures/planned-kmp-cases/expect_actual_incompatible_parameter_names/negative/jvm.kt': '423baae8a695dc7935487f08dd3aa13148e83f3f7e2b0a46f4033ebd6f6e6575',
 'providers/compiler-fixtures/planned-kmp-cases/expect_actual_incompatible_parameter_names/positive/common.kt': '9c091f7e1ce76cf587c919d33f8e1826b63af8e6b341d0766670e4acb1a539d7',
 'providers/compiler-fixtures/planned-kmp-cases/expect_actual_incompatible_parameter_names/positive/jvm.kt': '25667044f90ac9210f11d32e192125ee7e7796f3423cc81d7ca82462e4930f1f',
 'providers/compiler-fixtures/planned-kmp-cases/expect_actual_incompatible_property_kind/negative/common.kt': 'aa338d0fd3226c698f7034a0fa2dbd0f346d064299f5434152709ba9110e5221',
 'providers/compiler-fixtures/planned-kmp-cases/expect_actual_incompatible_property_kind/negative/jvm.kt': '6fcfc547634d0d4c562994018d9e8fcea80a9a598825123d0eaedebe1f85fa38',
 'providers/compiler-fixtures/planned-kmp-cases/expect_actual_incompatible_property_kind/positive/common.kt': 'aa338d0fd3226c698f7034a0fa2dbd0f346d064299f5434152709ba9110e5221',
 'providers/compiler-fixtures/planned-kmp-cases/expect_actual_incompatible_property_kind/positive/jvm.kt': 'd80892d07f9a9b44c5794967a8fd66a46b594962441209e0834e6998172b9d87',
 'providers/compiler-fixtures/planned-kmp-cases/expect_actual_incompatible_return_type/negative/common.kt': 'fb5bc1e6c7cd9b0fe7d5160eaa60496d9bbfd912f73a970ec31cc07a6561edcc',
 'providers/compiler-fixtures/planned-kmp-cases/expect_actual_incompatible_return_type/negative/jvm.kt': '30aca5e994c03968db323d8a960e7a2231b7c3ae8a016be0704ac2b7f2fe648d',
 'providers/compiler-fixtures/planned-kmp-cases/expect_actual_incompatible_return_type/positive/common.kt': 'fb5bc1e6c7cd9b0fe7d5160eaa60496d9bbfd912f73a970ec31cc07a6561edcc',
 'providers/compiler-fixtures/planned-kmp-cases/expect_actual_incompatible_return_type/positive/jvm.kt': 'a8c7ccec426e9d07b8cb30b413752f04c457e402929ca101766ec076725a217f',
 'providers/compiler-fixtures/planned-kmp-cases/expected_class_constructor_property_parameter/negative/common.kt': 'eebbd627632a00c82581b83ad31f3e5820bb3c60fe2937c7b488a9f61f6a8f44',
 'providers/compiler-fixtures/planned-kmp-cases/expected_class_constructor_property_parameter/negative/jvm.kt': '2be5886adad99217e7583cb8fb071807ebed97a5d8cec574efa34e94bc43a243',
 'providers/compiler-fixtures/planned-kmp-cases/expected_class_constructor_property_parameter/positive/common.kt': 'd96df699885265a2504226d06533bcd59ad67df451ab09f37892e3109d1b925a',
 'providers/compiler-fixtures/planned-kmp-cases/expected_class_constructor_property_parameter/positive/jvm.kt': '2be5886adad99217e7583cb8fb071807ebed97a5d8cec574efa34e94bc43a243',
 'providers/compiler-fixtures/planned-kmp-cases/expected_declaration_with_body/negative/common.kt': '23bd28a19b7b6dc4d6cb858fb7ad0d7198e54685713acd914f37bbcff102c183',
 'providers/compiler-fixtures/planned-kmp-cases/expected_declaration_with_body/negative/jvm.kt': '9ef05ed9e91d36f8bb9c154846e634acb09108ca9df0b6b0e76dc014cb3a8f3f',
 'providers/compiler-fixtures/planned-kmp-cases/expected_declaration_with_body/positive/common.kt': '935093e9ac8918ff762740db6975f319adc71fd6ed77a0c82951649adfa72139',
 'providers/compiler-fixtures/planned-kmp-cases/expected_declaration_with_body/positive/jvm.kt': '9ef05ed9e91d36f8bb9c154846e634acb09108ca9df0b6b0e76dc014cb3a8f3f',
 'providers/compiler-fixtures/planned-kmp-cases/expected_delegated_property/negative/common.kt': 'bda05c0057f7d8a101586de6c94271dd0310b44195e3001b0da5327f834b275c',
 'providers/compiler-fixtures/planned-kmp-cases/expected_delegated_property/negative/jvm.kt': '4bde8692249962d86f7db9c74c064204be9f9f43f69d83d593a2b4961d6b9c20',
 'providers/compiler-fixtures/planned-kmp-cases/expected_delegated_property/positive/common.kt': 'a7bdd91853cfc39144237c95e87762dbd37dbb69bb78f517082f9af94519b9e6',
 'providers/compiler-fixtures/planned-kmp-cases/expected_delegated_property/positive/jvm.kt': '4bde8692249962d86f7db9c74c064204be9f9f43f69d83d593a2b4961d6b9c20',
 'providers/compiler-fixtures/planned-kmp-cases/expected_lateinit_property/negative/common.kt': '436aba16e62a93b5ad7bbdaae08e11f2ecea924b2f1858f33fc8722d4627efb3',
 'providers/compiler-fixtures/planned-kmp-cases/expected_lateinit_property/negative/jvm.kt': '07c0077424a323e09686f187391676be2b1f34cfbf5440dc6e2e9c9e378362cd',
 'providers/compiler-fixtures/planned-kmp-cases/expected_lateinit_property/positive/common.kt': '8881059de9bdca45933135a94c189c36008bd1939ec4dab738f792d1686dd235',
 'providers/compiler-fixtures/planned-kmp-cases/expected_lateinit_property/positive/jvm.kt': '07c0077424a323e09686f187391676be2b1f34cfbf5440dc6e2e9c9e378362cd',
 'providers/compiler-fixtures/planned-kmp-cases/expected_private_declaration/negative/common.kt': 'ae3502679d4949879964d8dfec40236f6658680f02778372dd158c0462e99197',
 'providers/compiler-fixtures/planned-kmp-cases/expected_private_declaration/negative/jvm.kt': 'dec74cfaea4d1ef59d3da4c007fadaa93717d2a21cc0f14a96f1242c79700c67',
 'providers/compiler-fixtures/planned-kmp-cases/expected_private_declaration/positive/common.kt': 'bb22a7ff863861b3ad1ed042c5c5e7e1abdc04b4fe168aef707c54ac963565ca',
 'providers/compiler-fixtures/planned-kmp-cases/expected_private_declaration/positive/jvm.kt': 'dec74cfaea4d1ef59d3da4c007fadaa93717d2a21cc0f14a96f1242c79700c67',
 'providers/compiler-fixtures/planned-kmp-cases/expected_property_initializer/negative/common.kt': '9eef0d106ff629208b84eec24c3ea58c12a8a60c44970c66ae2924d7d412be16',
 'providers/compiler-fixtures/planned-kmp-cases/expected_property_initializer/negative/jvm.kt': '7236143c8ecf75778a7ee26fdfb39e980fcc745f2998cfa09092cfd877a96f5a',
 'providers/compiler-fixtures/planned-kmp-cases/expected_property_initializer/positive/common.kt': 'e07183f73a2a86f9732e8c6a08cd9118950ee5e39d3acbd0c045c71cfa8368a2',
 'providers/compiler-fixtures/planned-kmp-cases/expected_property_initializer/positive/jvm.kt': '7236143c8ecf75778a7ee26fdfb39e980fcc745f2998cfa09092cfd877a96f5a',
 'providers/compiler-fixtures/planned-kmp-cases/implementation_by_delegation_in_expect_class/negative/common.kt': '7017919ad679cee53ecb4470fa43f75c510cde99260b903e581f4179cc4c89c8',
 'providers/compiler-fixtures/planned-kmp-cases/implementation_by_delegation_in_expect_class/negative/jvm.kt': '185c45bd7ee96c625eb4f704357f97de73970d2e5f1feecc1224734d0fdcca4f',
 'providers/compiler-fixtures/planned-kmp-cases/implementation_by_delegation_in_expect_class/positive/common.kt': 'c6d73212b5b80104c8ba9abab922180c389524d12cfd31098f5beb5dc07e08e2',
 'providers/compiler-fixtures/planned-kmp-cases/implementation_by_delegation_in_expect_class/positive/jvm.kt': '185c45bd7ee96c625eb4f704357f97de73970d2e5f1feecc1224734d0fdcca4f',
 'providers/compiler-fixtures/planned-kmp-cases/no_actual_for_expect/negative/common.kt': 'fb8952a67548a9adae4023008c3e7d2381d9ca277675d2e54baebb3f13531043',
 'providers/compiler-fixtures/planned-kmp-cases/no_actual_for_expect/negative/jvm.kt': '651d92336da75d7101d643a35e11242630f3260b6ca18dc58e7d87d88d58f987',
 'providers/compiler-fixtures/planned-kmp-cases/no_actual_for_expect/positive/common.kt': 'fb8952a67548a9adae4023008c3e7d2381d9ca277675d2e54baebb3f13531043',
 'providers/compiler-fixtures/planned-kmp-cases/no_actual_for_expect/positive/jvm.kt': 'cb53917dfbdec05f5accc060070018a88b46f3764c5309147133e2208bd60d6a',
 'providers/compiler-fixtures/planned-kmp-cases/supertype_initialized_in_expected_class/negative/common.kt': '64a5c2c300ca71052e79b6a86bdf593ce1d6ec42fe24eb9c6b2fa49c4de55363',
 'providers/compiler-fixtures/planned-kmp-cases/supertype_initialized_in_expected_class/negative/jvm.kt': 'eaac8f47c8d8b618ff0965f00f349616f5abf6b6ba9d1d5d02140c6ca295bfed',
 'providers/compiler-fixtures/planned-kmp-cases/supertype_initialized_in_expected_class/positive/common.kt': '5c0068d3c95577c45c24c68070ebf6a54fd7729d643fe01ea706f9cd89bf7ab3',
 'providers/compiler-fixtures/planned-kmp-cases/supertype_initialized_in_expected_class/positive/jvm.kt': 'eaac8f47c8d8b618ff0965f00f349616f5abf6b6ba9d1d5d02140c6ca295bfed',
 'providers/compiler-fixtures/planned-kmp-manifest.json': '804c242c62707457ff143d16fb03bcd1bddd2ca84b3f9aef2085dc5af800b128',
 'providers/compiler-fixtures/test_checker_activation.py': '1c3839e950a30ee75d5fd3b8264345e035d003db8905ae46d2d4e24edcda417f',
 'providers/compiler-fixtures/test_compiler_mapping_contracts.py': '3b52e5967301b4e8344f2a5350610ea058d693becd5e8f6140d1e2f0815eec47',
 'providers/compiler-fixtures/test_diagnostic_breadth.py': '28e39186913a5cf34669d580bba563300991bcab616e8792bff8adc492ec6366',
 'providers/compiler-fixtures/test_diagnostic_breadth_hookup.py': 'dfb778571011d888848a734a4e18d82d1fb61b58b0a2ce8cf4895f7c4732b1ab',
 'providers/compiler-fixtures/test_nullability_protocols.py': 'f84ea747138b77516f8283dc4ca80ac40077cac9c7336f59e93167d61cf488d6',
 'providers/compiler-fixtures/test_nullability_protocols_hookup.py': 'b4c240881cbbae10c2865c9d65b67842b336478c916d933dec8d91f08554a88b',
 'providers/compiler-fixtures/test_original42_selectors.py': '1d4978d9fff70ae2f6056c657506894de6fbeb7d101a9d2e25db27df5404cbb5',
 'providers/compiler-fixtures/test_type_boundaries.py': '14377ac77891cdcc78117d88ecabb7e52d0980ac23aab82e45e3814c28712124',
 'providers/compiler-fixtures/test_type_boundaries_supplemental.py': '4675344b40264a2febdb033acdbe91e79038902c62f62102818603caabe321fc'}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read_source(root, relative):
    require(isinstance(relative, str) and relative and ':' not in relative and '\\' not in relative and '\x00' not in relative,
            'Invalid SOURCE path')
    parts = PurePosixPath(relative)
    require(not parts.is_absolute() and parts.as_posix() == relative and all(part not in ('', '.', '..') for part in parts.parts),
            'SOURCE path must be canonical relative POSIX')
    root = Path(root).resolve()
    path = root
    for part in parts.parts:
        path = path / part
        require(not path.is_symlink(), 'Symlink SOURCE input')
    require(path.is_file() and path.stat().st_size <= 8_388_608, 'Missing or oversized SOURCE input')
    return path.read_bytes()


def legacy_source_bytes(root, relative):
    """Explicit immutable old SOURCE data, not a virtual repository or executable fallback."""
    pin = LEGACY_DATA_PINS.get(relative)
    require(pin is not None, 'Not one of the closed legacy SOURCE data inputs')
    data = read_source(root, DATA_REL + '/' + pin['file'])
    require((len(data), digest(data)) == (pin['bytes'], pin['sha256']), 'Legacy SOURCE data drift')
    return data


# Only these two reviewed authoring SOURCE changes may reconstruct their frozen
# preimages. They never load a legacy catalog into runtime or grant execution credit.
AUTHORING_SOURCE_TRANSITIONS = {'providers/compiler-fixtures/generate_fixtures.py': {'preimage_bytes': 16716, 'preimage_sha256': '73cfab45c982081da4088daa6fc2b266f16d24c828010772966ea201d1372b69', 'successor_bytes': 16980, 'successor_sha256': 'b46220dcf987229173094db7525b2e3645c345d0f81eb7814f7d37b56eb11faf', 'reverse_changes': ((b'# Frozen catalog size of this legacy authored source-plan inventory, not the\n# active445 catalog or the separate current445 route. Live rule lookup above\n# still includes every current compiler mapping.\nLEGACY_AUTHORED_CATALOG_MAPPINGS = 443\n', b''), (b"    'catalog_mappings': LEGACY_AUTHORED_CATALOG_MAPPINGS,\n", b"    'catalog_mappings': len(rules),\n"))}, 'providers/compiler-fixtures/test_type_boundaries.py': {'preimage_bytes': 17913, 'preimage_sha256': '6bb1a15f884b382593ee797fe4c09d0ec65399a600ffbda9574efa6c7f67456d', 'successor_bytes': 19186, 'successor_sha256': '14377ac77891cdcc78117d88ecabb7e52d0980ac23aab82e45e3814c28712124', 'reverse_changes': ((b"        # Bind only the frozen legacy source plan, never the active445 catalog.\n        generator = ast.parse((FIXTURES / 'generate_fixtures.py').read_text(encoding='utf-8'))\n\n        def assigned_value(name):\n            values = [node.value for node in generator.body\n                      if isinstance(node, ast.Assign) and len(node.targets) == 1\n                      and isinstance(node.targets[0], ast.Name) and node.targets[0].id == name]\n            self.assertEqual(1, len(values), name)\n            return values[0]\n\n        legacy_size = assigned_value('LEGACY_AUTHORED_CATALOG_MAPPINGS')\n        self.assertIsInstance(legacy_size, ast.Constant)\n        self.assertIs(int, type(legacy_size.value))\n        self.assertEqual(443, legacy_size.value)\n        generated_inventory = assigned_value('inventory')\n        self.assertIsInstance(generated_inventory, ast.Dict)\n        catalog_values = [value for key, value in zip(generated_inventory.keys, generated_inventory.values)\n                          if isinstance(key, ast.Constant) and key.value == 'catalog_mappings']\n        self.assertEqual(1, len(catalog_values))\n        self.assertIsInstance(catalog_values[0], ast.Name)\n        self.assertEqual('LEGACY_AUTHORED_CATALOG_MAPPINGS', catalog_values[0].id)\n", b''),)}}


def authoring_predecessor_bytes(relative, data):
    """Closed exact SOURCE inverse, not a generic patch engine or executable fallback."""
    pin = AUTHORING_SOURCE_TRANSITIONS.get(relative)
    require(pin is not None, 'Not a closed authoring SOURCE transition')
    require((len(data), digest(data)) == (pin['successor_bytes'], pin['successor_sha256']),
            'Authoring SOURCE successor drift')
    prior = data
    for successor, predecessor in pin['reverse_changes']:
        require(prior.count(successor) == 1, 'Ambiguous authoring SOURCE inverse')
        prior = prior.replace(successor, predecessor, 1)
    require((len(prior), digest(prior)) == (pin['preimage_bytes'], pin['preimage_sha256']),
            'Authoring SOURCE preimage drift')
    return prior


# Exactly these two reviewed diagnostics SOURCE successors may reconstruct their
# independently frozen predecessors. This table is separate from authoring changes;
# the provider predecessor is not its older immutable legacy443 DATA.
DIAGNOSTICS_SOURCE_TRANSITIONS = {
    'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/CompilerDiagnosticParser.kt': {
        'preimage_bytes': 6408,
        'preimage_sha256': 'db0048e7083bfc8d1565d0505d1b3c00ad574e6e50ef2dc39797a947912e3f3e',
        'successor_bytes': 12953,
        'successor_sha256': 'f6a1d168873a4a47120110968095e766435526fa91f75749df414253a1a5028a',
        'reverse_changes': (
            (b'        var pendingMembers: AbstractMemberBlock? = null\n        fun abandonMembers() {\n            val block = pendingMembers ?: return\n            if (block.members.isEmpty()) {\n                // A header cut off before its first member must still leave raw\n                // evidence: consumers must not mistake an empty raw list for a\n                // complete task. Retain the genuine header, not an invented row.\n                val diagnostic = diagnostics[block.diagnosticIndex]\n                raw += RawDiagnostic(diagnostic.message, diagnostic.severity,\n                    diagnostic.upstreamId, diagnostic.location)\n            }\n            block.members.forEach { raw += RawDiagnostic(it, "info") }\n            if (block.sourceSeen) raw += RawDiagnostic(block.sourceRow.take(MAX_MESSAGE), "info")\n            malformed = true\n            pendingMembers = null\n        }\n', b''),
            (b'            val block = pendingMembers\n            if (block != null) {\n                // A new message always wins, even if adversarial frozen source\n                // text happens to equal a header. Never consume another emission.\n                val boundary = VERSION.matches(line) || COLON_HEADER.matches(line) ||\n                    PAREN_HEADER.matches(line) || GLOBAL_HEADER.matches(line)\n                if (!boundary) {\n                    if (block.sourceSeen) {\n                        if (block.matchesCaret(line)) {\n                            val diagnostic = diagnostics[block.diagnosticIndex]\n                            diagnostics[block.diagnosticIndex] = diagnostic.copy(\n                                message = diagnostic.message + "\\n" + block.members.joinToString("\\n"))\n                            pendingMembers = null\n                            return@forEach\n                        }\n                    } else if (line == block.sourceRow) {\n                        if (memberCountMatches(block.plural, block.members.size)) {\n                            block.sourceSeen = true\n                            return@forEach\n                        }\n                    } else if (block.members.size < MAX_ABSTRACT_MEMBERS &&\n                        (block.plural || block.members.isEmpty()) &&\n                        line.length <= MAX_MESSAGE - block.messageSize - 1 && isSimpleMember(line)) {\n                        block.members += line\n                        block.messageSize += line.length + 1\n                        return@forEach\n                    }\n                }\n                // No partial attachment: retain the buffered rows as raw, then\n                // process this row normally, including headers and global errors.\n                abandonMembers()\n            }\n', b''),
            (b'                    val diagnostic = ParsedCompilerDiagnostic(named.groupValues[1], severity,\n                        named.groupValues[3].take(MAX_MESSAGE), location,\n                        named.groupValues[2].takeIf { it.isNotBlank() })\n                    diagnostics += diagnostic\n                    val sourceRow = identity!!.text.split(\'\\n\')[reportedLine!! - 1].removeSuffix("\\r")\n                    val plural = abstractMemberPlural(diagnostic.upstreamId, severity, diagnostic.message)\n                    if (body.length <= MAX_MESSAGE && plural != null) {\n                        pendingMembers = AbstractMemberBlock(diagnostics.lastIndex, sourceRow,\n                            location.column, plural, diagnostic.message.length)\n                    } else {\n                        expectedSourceRow = sourceRow\n                    }\n', b'                    diagnostics += ParsedCompilerDiagnostic(named.groupValues[1], severity,\n                        named.groupValues[3].take(MAX_MESSAGE), location,\n                        named.groupValues[2].takeIf { it.isNotBlank() })\n                    expectedSourceRow = identity!!.text.split(\'\\n\')[reportedLine!! - 1].removeSuffix("\\r")\n'),
            (b'        abandonMembers() // EOF is not a successful source/caret fence.\n', b''),
            (b"    private class AbstractMemberBlock(\n        val diagnosticIndex: Int,\n        val sourceRow: String,\n        val column: Int,\n        val plural: Boolean,\n        var messageSize: Int,\n    ) {\n        val members = mutableListOf<String>()\n        var sourceSeen = false\n\n        fun matchesCaret(line: String): Boolean {\n            val width = line.length - column\n            if (width < 1 || width > maxOf(1, sourceRow.length - column)) return false\n            return line.indices.all { line[it] == if (it < column) ' ' else '^' }\n        }\n    }\n\n", b''),
            (b'        private const val MAX_ABSTRACT_MEMBERS = 32\n        private const val ABSTRACT_MEMBER_ID = "ABSTRACT_MEMBER_NOT_IMPLEMENTED"\n        private const val SIMPLE_NAME = "[A-Za-z_][A-Za-z0-9_]*"\n', b''),
            (b'\n        // Kotlin v2.4.21 FirErrorsDefaultMessages + SYMBOLS_ON_NEXT_LINES:\n        // no indentation, one body/accessor-free symbol per line. This is an\n        // intentionally small subset, not a general Kotlin signature parser.\n        private val ABSTRACT_MEMBER_HEADER = Regex(\n            "^(?:Class|Object|Interface) \'[^\'\\\\r\\\\n]+\' is not abstract and does not implement abstract (member|members):$")\n        private val SIMPLE_TYPE = "$SIMPLE_NAME\\\\??"\n        private val SIMPLE_PROPERTY = Regex("^(?:val|var) $SIMPLE_NAME: $SIMPLE_TYPE$")\n        private val SIMPLE_FUNCTION = Regex("^(?:suspend )?fun $SIMPLE_NAME\\\\(([^()]*)\\\\): $SIMPLE_TYPE$")\n        private val SIMPLE_PARAMETER = Regex("^$SIMPLE_NAME: $SIMPLE_TYPE$")\n\n        private fun abstractMemberPlural(upstreamId: String, severity: String, header: String): Boolean? {\n            if (upstreamId != ABSTRACT_MEMBER_ID || severity != "error") return null\n            val match = ABSTRACT_MEMBER_HEADER.matchEntire(header) ?: return null\n            return match.groupValues[1] == "members"\n        }\n\n        private fun memberCountMatches(plural: Boolean, count: Int): Boolean =\n            if (plural) count in 2..MAX_ABSTRACT_MEMBERS else count == 1\n\n        private fun isSimpleMember(line: String): Boolean {\n            if (SIMPLE_PROPERTY.matches(line)) return true\n            val function = SIMPLE_FUNCTION.matchEntire(line) ?: return false\n            val parameters = function.groupValues[1]\n            return parameters.isEmpty() || parameters.split(", ").all { SIMPLE_PARAMETER.matches(it) }\n        }\n\n        // parse attaches these rows only after the complete source/caret fence.\n        // The provider calls this only on its existing trusted, selected finding\n        // path. Advice is not an implementation, verified fix or qualification.\n        internal fun fixHint(diagnostic: ParsedCompilerDiagnostic): String? {\n            if (diagnostic.location == null || diagnostic.message.length > MAX_MESSAGE) return null\n            val rows = diagnostic.message.split(\'\\n\')\n            val plural = abstractMemberPlural(diagnostic.upstreamId, diagnostic.severity, rows.first()) ?: return null\n            if (!memberCountMatches(plural, rows.size - 1) || rows.drop(1).any { !isSimpleMember(it) }) return null\n            return "Implement the listed abstract members with compatible overrides, or inherit compatible concrete " +\n                "implementations. Recompile in the same context to verify."\n        }\n', b''),
        ),
    },
    'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/KotlinJvmCompilerProvider.kt': {
        'preimage_bytes': 28750,
        'preimage_sha256': '8ee53a10c3eeff45dc6dc3bfa717d7e901515a74a7998327c7380912309f7a78',
        'successor_bytes': 29188,
        'successor_sha256': 'a8cec0f648ad66e22cf8b911283500fa3a761b56d20f2abdbf3b13e3f55d9112',
        'reverse_changes': (
            (b'                mapping.kind, mapping.certainty, fixHint = CompilerDiagnosticParser.fixHint(diagnostic),\n                evidence = Collections.unmodifiableList(listOf(\n', b'                mapping.kind, mapping.certainty, evidence = Collections.unmodifiableList(listOf(\n'),
            (b'                incompleteCompilerTask(outcome.exitCode, parsed, raw) -> { state = "failed"; reason = "failed_or_incomplete_compiler_task" }\n', b'                outcome.exitCode != 0 || raw.isNotEmpty() -> { state = "failed"; reason = "failed_or_incomplete_compiler_task" }\n'),
            (b'        // The caller supplies its post-normalization raw list. Clearing this one\n        // barrier says nothing about execution trust, scope or qualification.\n        internal fun incompleteCompilerTask(exitCode: Int?, parsed: CompilerParseResult,\n            raw: List<RawDiagnostic>): Boolean = exitCode != 0 || parsed.malformed || raw.isNotEmpty()\n\n', b''),
        ),
    },
}


def diagnostics_predecessor_bytes(relative, data):
    """Two exact SOURCE inverses; never a generic patch engine or executable fallback."""
    pin = DIAGNOSTICS_SOURCE_TRANSITIONS.get(relative)
    require(pin is not None, 'Not a closed diagnostics SOURCE transition')
    require((len(data), digest(data)) == (pin['successor_bytes'], pin['successor_sha256']),
            'Diagnostics SOURCE successor drift')
    prior = data
    for successor, predecessor in pin['reverse_changes']:
        require(prior.count(successor) == 1, 'Ambiguous diagnostics SOURCE inverse')
        prior = prior.replace(successor, predecessor, 1)
    require((len(prior), digest(prior)) == (pin['preimage_bytes'], pin['preimage_sha256']),
            'Diagnostics SOURCE preimage drift')
    return prior


# Separate from both existing diagnostics and authoring SOURCE inverses. The provider
# restores exact core-v2 before that older diagnostics inverse; Workbench is prospective
# SOURCE control only and is never added to historical_source_bytes' closed domain.
OBSERVATION_SOURCE_TRANSITIONS = {
    'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/KotlinJvmCompilerProvider.kt': {
        'preimage_bytes': 29188,
        'preimage_sha256': 'a8cec0f648ad66e22cf8b911283500fa3a761b56d20f2abdbf3b13e3f55d9112',
        'successor_bytes': 29802,
        'successor_sha256': '8bfcb578f6a23203619fdc5f4257eb46580b39b65c9ae553b50e3f0a9d7dcb16',
        'reverse_changes': (
            (
                b'        timings: CompilerPhaseTimings): ProviderResult = analyzeWithTimings(input, cancellation, timings)\n\n    /** Owner handle only; the existing test-recorder entry and default path remain separate. */\n    internal fun analyzeObserved(input: AnalysisInput, cancellation: CancellationToken,\n        capture: CompilerCallCapture): ProviderResult = analyzeWithTimings(input, cancellation, capture.timings, capture)\n\n    private fun analyzeWithTimings(input: AnalysisInput, cancellation: CancellationToken,\n        timings: CompilerPhaseTimings?, capture: CompilerCallCapture? = null): ProviderResult {\n        val started = System.nanoTime()\n        return timings.observe(CompilerPhase.PROVIDER_CALL) { analyzeImpl(input, cancellation, started, timings, capture) }\n    }\n\n    private fun analyzeImpl(input: AnalysisInput, cancellation: CancellationToken, started: Long,\n        timings: CompilerPhaseTimings?, capture: CompilerCallCapture?): ProviderResult {\n        val request = freeze(input)\n        val required = request.requiredRuleIds.distinct()\n',
                b'        timings: CompilerPhaseTimings): ProviderResult = analyzeWithTimings(input, cancellation, timings)\n\n    private fun analyzeWithTimings(input: AnalysisInput, cancellation: CancellationToken,\n        timings: CompilerPhaseTimings?): ProviderResult {\n        val started = System.nanoTime()\n        return timings.observe(CompilerPhase.PROVIDER_CALL) { analyzeImpl(input, cancellation, started, timings) }\n    }\n\n    private fun analyzeImpl(input: AnalysisInput, cancellation: CancellationToken, started: Long,\n        timings: CompilerPhaseTimings?): ProviderResult {\n        val request = freeze(input)\n        val required = request.requiredRuleIds.distinct()\n',
            ),
            (
                b'            ?: return unavailable("execution_not_authorized")\n        val execution = request.execution ?: return unavailable("execution_profile_missing")\n        val actualKey = request.analysisKey()\n        if (permit.expectedAnalysisKey != actualKey || permit.snapshotId != request.snapshot.snapshotId ||\n            permit.materializedRoot.toAbsolutePath().normalize() != Path.of(request.materializedRoot).toAbsolutePath().normalize()) {\n            return unavailable("execution_identity_binding_mismatch")\n',
                b'            ?: return unavailable("execution_not_authorized")\n        val execution = request.execution ?: return unavailable("execution_profile_missing")\n        if (permit.expectedAnalysisKey != request.analysisKey() || permit.snapshotId != request.snapshot.snapshotId ||\n            permit.materializedRoot.toAbsolutePath().normalize() != Path.of(request.materializedRoot).toAbsolutePath().normalize()) {\n            return unavailable("execution_identity_binding_mismatch")\n',
            ),
            (
                b'            if (System.nanoTime() >= deadline) throw ExecutionProblem("compiler_preparation_timeout", "Compiler preparation deadline elapsed")\n        }\n        capture?.captureIdentity(actualKey, request, toolchain.digest, registry.mappingIdentity.resourceDigest, compilerHeapMiB)\n        val sources: List<SealedCompilerSource>\n        try {\n',
                b'            if (System.nanoTime() >= deadline) throw ExecutionProblem("compiler_preparation_timeout", "Compiler preparation deadline elapsed")\n        }\n        val sources: List<SealedCompilerSource>\n        try {\n',
            ),
            (
                b'            status = "failed", reasons = (ran.reasons.filterNot { it == "execution_cancelled" } + "compiler_total_timeout").distinct(),\n            cancelled = false, timedOut = true) else ran\n        capture?.captureOutcomes(ran, outcome, supportedRuleIds)\n        timings.observe(CompilerPhase.EXECUTION_OBSERVER) { executionObserver(request, outcome) }\n        return timings.observe(CompilerPhase.RESULT_NORMALIZATION) {\n',
                b'            status = "failed", reasons = (ran.reasons.filterNot { it == "execution_cancelled" } + "compiler_total_timeout").distinct(),\n            cancelled = false, timedOut = true) else ran\n        timings.observe(CompilerPhase.EXECUTION_OBSERVER) { executionObserver(request, outcome) }\n        return timings.observe(CompilerPhase.RESULT_NORMALIZATION) {\n',
            ),
        ),
    },
    'modules/cli/src/main/kotlin/dev/ktchecker/cli/Workbench.kt': {
        'preimage_bytes': 41976,
        'preimage_sha256': '011a88a770055032d7ce07513d8c05c5aa4fa3f6a5fd743385a44bf25bd38209',
        'successor_bytes': 42659,
        'successor_sha256': 'f37d4784f760379b386e195c1f1d3115201578caf707b04e43b5da65829f16ab',
        'reverse_changes': (
            (
                b'package dev.ktchecker.cli\n\nimport dev.ktchecker.compiler.CompilerCallObservation\nimport dev.ktchecker.compiler.KotlinJvmCompilerProvider\nimport dev.ktchecker.compiler.KotlinJvmInputMode\n',
                b'package dev.ktchecker.cli\n\nimport dev.ktchecker.compiler.KotlinJvmCompilerProvider\nimport dev.ktchecker.compiler.KotlinJvmInputMode\n',
            ),
            (
                b'    /** Explicit owner runtime, selected by the owner model command or typed owner composition, never RPC. */\n    val liveModelRuntime: OwnerLiveModelRuntime? = null,\n    /** Typed owner composition only; never a CLI/RPC/serialized request option. */\n    val compilerCallObservation: CompilerCallObservation? = null,\n) {\n    init { require(modelCompilerInstallation == null || liveModelRuntime == null) { "Select one exact model execution mode; no fallback" } }\n',
                b'    /** Explicit owner runtime, selected by the owner model command or typed owner composition, never RPC. */\n    val liveModelRuntime: OwnerLiveModelRuntime? = null,\n) {\n    init { require(modelCompilerInstallation == null || liveModelRuntime == null) { "Select one exact model execution mode; no fallback" } }\n',
            ),
            (
                b'                ContractJson.encodeToJsonElement(modelCompilerInstallation.compilerClasspath))),\n            preparedChild = modelGrants::child, finishAttempt = modelGrants::finish)) }\n        compiler?.let { provider -> add(ProviderRoute(\n            ordinaryCompilerProviderWithObservation(provider, options.compilerCallObservation, codeImage.digest),\n            { !isModelAttempt(it) && it.context.scope.target.lowercase() in setOf("jvm", "desktop", "android") && it.context.compilerVersion == "2.4.21" }, { provider.executionProfile(it) },\n            Digests.sha256(providerIdentity(provider) + compilerInstallation!!.digest + Digests.canonical(\n',
                b'                ContractJson.encodeToJsonElement(modelCompilerInstallation.compilerClasspath))),\n            preparedChild = modelGrants::child, finishAttempt = modelGrants::finish)) }\n        compiler?.let { provider -> add(ProviderRoute(provider,\n            { !isModelAttempt(it) && it.context.scope.target.lowercase() in setOf("jvm", "desktop", "android") && it.context.compilerVersion == "2.4.21" }, { provider.executionProfile(it) },\n            Digests.sha256(providerIdentity(provider) + compilerInstallation!!.digest + Digests.canonical(\n',
            ),
            (
                b'}\n\n/** Null is an exact reference-preserving bypass: no wrapper, recorder or observation clock. */\ninternal fun ordinaryCompilerProviderWithObservation(provider: KotlinJvmCompilerProvider,\n    observation: CompilerCallObservation?, ownerCodeDigest: String): AnalysisProvider =\n    if (observation == null) provider else observation.observedProvider(provider, ownerCodeDigest)\n\n/** Preference only. The provider independently verifies all execution/config/artifact prerequisites. */\ninternal fun ktlintPreferred(input: AnalysisInput, installed: Boolean): Boolean = installed &&\n',
                b'}\n\n/** Preference only. The provider independently verifies all execution/config/artifact prerequisites. */\ninternal fun ktlintPreferred(input: AnalysisInput, installed: Boolean): Boolean = installed &&\n',
            ),
        ),
    },
}


def observation_predecessor_bytes(relative, data):
    """Two exact observation SOURCE inverses; no runtime wrapper or arbitrary fallback."""
    pin = OBSERVATION_SOURCE_TRANSITIONS.get(relative)
    require(pin is not None, 'Not a closed observation SOURCE transition')
    require((len(data), digest(data)) == (pin['successor_bytes'], pin['successor_sha256']),
            'Observation SOURCE successor drift')
    prior = data
    for successor, predecessor in pin['reverse_changes']:
        require(prior.count(successor) == 1, 'Ambiguous observation SOURCE inverse')
        prior = prior.replace(successor, predecessor, 1)
    require((len(prior), digest(prior)) == (pin['preimage_bytes'], pin['preimage_sha256']),
            'Observation SOURCE preimage drift')
    return prior


# One later SOURCE-only parser transition. Preserve the earlier diagnostics2 and
# observation2 inverses exactly; the predecessor here is the reviewed f6a parser.
PARSER_AMBIGUITY_SOURCE_TRANSITIONS = {
    'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/CompilerDiagnosticParser.kt': {
        'preimage_bytes': 12953,
        'preimage_sha256': 'f6a1d168873a4a47120110968095e766435526fa91f75749df414253a1a5028a',
        'successor_bytes': 17032,
        'successor_sha256': '48fa09fc3f57241160b1d6d964c4a41f01edead05354afbcc5f2cde9f62a372d',
        'reverse_changes': (
            (
                b'class CompilerDiagnosticParser {\n    fun parse(output: String, sourcePaths: Map<Path, CompilerSourceIdentity>): CompilerParseResult {\n        val identities = sourcePaths.mapKeys { it.key.toAbsolutePath().normalize().toString() }\n        val messageShapedSourceRows = mutableSetOf<String>()\n        var retainedSourceChars = 0\n        var ambiguityLimitExceeded = false\n        // Census the complete output before promoting any row. A source echo\n        // can precede the located header that supplies its immutable witness.\n        // Learn only rows referenced by valid located headers, including unnamed\n        // headers; do not search unreferenced source text for diagnostic names.\n        for (original in output.lineSequence()) {\n            val line = original.trimEnd(\'\\r\')\n            if (VERSION.matches(line)) continue // Same precedence as the parse.\n            val header = COLON_HEADER.matchEntire(line) ?: PAREN_HEADER.matchEntire(line) ?: continue\n            val identity = resolveIdentity(header.groupValues[1], identities) ?: continue\n            val reportedLine = header.groupValues[2].toIntOrNull() ?: continue\n            val reportedColumn = header.groupValues[3].toIntOrNull() ?: continue\n            val sourceRow = validSourceRow(identity.text, reportedLine, reportedColumn) ?: continue\n            if (!isMessageShapedRow(sourceRow) || sourceRow in messageShapedSourceRows) continue\n            // String.length counts UTF-16 units, like the renderer coordinates.\n            // Check duplicates first and subtract from the fixed aggregate cap.\n            if (sourceRow.length > MAX_MESSAGE || messageShapedSourceRows.size >= MAX_AMBIGUOUS_SOURCE_ROWS ||\n                sourceRow.length > MAX_AMBIGUOUS_SOURCE_CHARS - retainedSourceChars) {\n                ambiguityLimitExceeded = true\n                break // No prefix can be proved independent of later witnesses.\n            }\n            messageShapedSourceRows += sourceRow\n            retainedSourceChars += sourceRow.length\n        }\n        val diagnostics = mutableListOf<ParsedCompilerDiagnostic>()\n        val raw = mutableListOf<RawDiagnostic>()\n        var version: String? = null\n        var malformed = ambiguityLimitExceeded\n        if (ambiguityLimitExceeded) raw += RawDiagnostic(AMBIGUITY_LIMIT_MESSAGE, "info")\n        var expectedSourceRow: String? = null\n        var expectedCaret = false\n',
                b'class CompilerDiagnosticParser {\n    fun parse(output: String, sourcePaths: Map<Path, CompilerSourceIdentity>): CompilerParseResult {\n        val diagnostics = mutableListOf<ParsedCompilerDiagnostic>()\n        val raw = mutableListOf<RawDiagnostic>()\n        var version: String? = null\n        var malformed = false\n        var expectedSourceRow: String? = null\n        var expectedCaret = false\n',
            ),
            (
                b"            pendingMembers = null\n        }\n        output.lineSequence().forEach { original ->\n            val line = original.trimEnd('\\r')\n            if (line in messageShapedSourceRows || (ambiguityLimitExceeded && isMessageShapedRow(line))) {\n                // Equality is ambiguous evidence, not a source/caret fence or\n                // another emission. The whole-parse census also covers a row\n                // equal to its own resolved source and echoes after abandonment.\n                // On overflow this applies to ALL message rows, even the prefix.\n                abandonMembers()\n                expectedSourceRow = null\n                expectedCaret = false\n                raw += quarantinedMessage(line)\n                malformed = true\n                return@forEach\n            }\n            val block = pendingMembers\n            if (block != null) {\n                // A noncolliding new message wins. Header-shaped source rows\n                // have already been quarantined using the whole-output census.\n                val boundary = isMessageShapedRow(line)\n                if (!boundary) {\n                    if (block.sourceSeen) {\n",
                b"            pendingMembers = null\n        }\n        val identities = sourcePaths.mapKeys { it.key.toAbsolutePath().normalize().toString() }\n        output.lineSequence().forEach { original ->\n            val line = original.trimEnd('\\r')\n            val block = pendingMembers\n            if (block != null) {\n                // A new message always wins, even if adversarial frozen source\n                // text happens to equal a header. Never consume another emission.\n                val boundary = VERSION.matches(line) || COLON_HEADER.matches(line) ||\n                    PAREN_HEADER.matches(line) || GLOBAL_HEADER.matches(line)\n                if (!boundary) {\n                    if (block.sourceSeen) {\n",
            ),
            (
                b'                val named = NAMED.matchEntire(body)\n                val identity = resolveIdentity(emittedPath, identities)\n                val sourceRow = if (identity != null && reportedLine != null && reportedColumn != null) {\n                    validSourceRow(identity.text, reportedLine, reportedColumn)\n                } else null\n                val location = if (identity != null && reportedLine != null && reportedColumn != null && sourceRow != null) {\n                    SourceLocation(identity.path, identity.digest, reportedLine, reportedColumn - 1,\n                        reportedLine, reportedColumn - 1)\n                } else null\n                if (named == null || location == null || sourceRow == null) {\n                    raw += RawDiagnostic(body.take(MAX_MESSAGE), severity, named?.groupValues?.get(1), location)\n                    malformed = true\n',
                b'                val named = NAMED.matchEntire(body)\n                val identity = resolveIdentity(emittedPath, identities)\n                val location = if (identity != null && reportedLine != null && reportedColumn != null &&\n                    validPoint(identity.text, reportedLine, reportedColumn)) {\n                    SourceLocation(identity.path, identity.digest, reportedLine, reportedColumn - 1,\n                        reportedLine, reportedColumn - 1)\n                } else null\n                if (named == null || location == null) {\n                    raw += RawDiagnostic(body.take(MAX_MESSAGE), severity, named?.groupValues?.get(1), location)\n                    malformed = true\n',
            ),
            (
                b'                        named.groupValues[2].takeIf { it.isNotBlank() })\n                    diagnostics += diagnostic\n                    val plural = abstractMemberPlural(diagnostic.upstreamId, severity, diagnostic.message)\n                    if (body.length <= MAX_MESSAGE && plural != null) {\n',
                b'                        named.groupValues[2].takeIf { it.isNotBlank() })\n                    diagnostics += diagnostic\n                    val sourceRow = identity!!.text.split(\'\\n\')[reportedLine!! - 1].removeSuffix("\\r")\n                    val plural = abstractMemberPlural(diagnostic.upstreamId, severity, diagnostic.message)\n                    if (body.length <= MAX_MESSAGE && plural != null) {\n',
            ),
            (
                b'    }\n\n    private fun validSourceRow(text: String, line: Int, column: Int): String? {\n        if (line < 1 || column < 1) return null\n        val row = text.splitToSequence(\'\\n\').drop(line - 1).firstOrNull()?.removeSuffix("\\r") ?: return null\n        return row.takeIf { column - 1 <= it.length }\n    }\n\n    private fun isMessageShapedRow(line: String): Boolean =\n        VERSION.matches(line) || COLON_HEADER.matches(line) || PAREN_HEADER.matches(line) || GLOBAL_HEADER.matches(line)\n\n    private fun quarantinedMessage(line: String): RawDiagnostic {\n        // Preserve the old global body/severity/name shape, but never assign a\n        // location to an ambiguous row. A claimed name is not a parsed finding.\n        // Bound the body BEFORE extracting its name; an oversized identifier\n        // must not survive via RawDiagnostic.upstreamId after message truncation.\n        if (VERSION.matches(line)) return RawDiagnostic(line.take(MAX_MESSAGE), "info")\n        val header = COLON_HEADER.matchEntire(line) ?: PAREN_HEADER.matchEntire(line)\n        if (header != null) {\n            val body = header.groupValues[5].take(MAX_MESSAGE)\n            return RawDiagnostic(body, header.groupValues[4], NAMED.matchEntire(body)?.groupValues?.get(1))\n        }\n        val global = GLOBAL_HEADER.matchEntire(line)\n        if (global != null) {\n            val body = global.groupValues[2].take(MAX_MESSAGE)\n            return RawDiagnostic(body, global.groupValues[1], NAMED.matchEntire(body)?.groupValues?.get(1))\n        }\n        return RawDiagnostic(line.take(MAX_MESSAGE), "info")\n    }\n\n',
                b'    }\n\n    private fun validPoint(text: String, line: Int, column: Int): Boolean {\n        if (line < 1 || column < 1) return false\n        val sourceLine = text.split(\'\\n\').getOrNull(line - 1)?.removeSuffix("\\r") ?: return false\n        return column <= sourceLine.length + 1\n    }\n\n',
            ),
            (
                b'        const val MAX_MESSAGE = 16_384\n        private const val MAX_ABSTRACT_MEMBERS = 32\n        private const val MAX_AMBIGUOUS_SOURCE_ROWS = 64\n        private const val MAX_AMBIGUOUS_SOURCE_CHARS = 4 * MAX_MESSAGE\n        private const val AMBIGUITY_LIMIT_MESSAGE = "Compiler header/source ambiguity tracking limit exceeded"\n        private const val ABSTRACT_MEMBER_ID = "ABSTRACT_MEMBER_NOT_IMPLEMENTED"\n        private const val SIMPLE_NAME = "[A-Za-z_][A-Za-z0-9_]*"\n',
                b'        const val MAX_MESSAGE = 16_384\n        private const val MAX_ABSTRACT_MEMBERS = 32\n        private const val ABSTRACT_MEMBER_ID = "ABSTRACT_MEMBER_NOT_IMPLEMENTED"\n        private const val SIMPLE_NAME = "[A-Za-z_][A-Za-z0-9_]*"\n',
            ),
        ),
    },
}


def parser_ambiguity_predecessor_bytes(relative, data):
    """One exact parser SOURCE inverse; not a parser execution or historical fallback."""
    pin = PARSER_AMBIGUITY_SOURCE_TRANSITIONS.get(relative)
    require(pin is not None, 'Not a closed parser ambiguity SOURCE transition')
    require((len(data), digest(data)) == (pin['successor_bytes'], pin['successor_sha256']),
            'Parser ambiguity SOURCE successor drift')
    prior = data
    for successor, predecessor in pin['reverse_changes']:
        require(prior.count(successor) == 1, 'Ambiguous parser ambiguity SOURCE inverse')
        prior = prior.replace(successor, predecessor, 1)
    require((len(prior), digest(prior)) == (pin['preimage_bytes'], pin['preimage_sha256']),
            'Parser ambiguity SOURCE preimage drift')
    return prior


def historical_source_bytes(root, relative):
    """Explicit old DATA, separately closed SOURCE inverses or unchanged joins; no arbitrary fallback."""
    if relative in AUTHORING_SOURCE_TRANSITIONS:
        return authoring_predecessor_bytes(relative, read_source(root, relative))
    if relative in LEGACY_DATA_PINS:
        return legacy_source_bytes(root, relative)
    if relative in DIAGNOSTICS_SOURCE_TRANSITIONS:
        data = read_source(root, relative)
        if relative in PARSER_AMBIGUITY_SOURCE_TRANSITIONS:
            data = parser_ambiguity_predecessor_bytes(relative, data)
        return diagnostics_predecessor_bytes(relative, data)
    expected = UNCHANGED_SOURCE_PINS.get(relative)
    require(expected is not None, 'Not a closed unchanged SOURCE join')
    data = read_source(root, relative)
    require(digest(data) == expected, 'Unchanged SOURCE join drift')
    return data


def legacy_fixture_bytes(root, fixture_relative):
    return historical_source_bytes(root, 'providers/compiler-fixtures/' + fixture_relative)


def legacy_mapping(root):
    return json.loads(legacy_source_bytes(root, MAP_REL))


def pre_activation_mapping_bytes(root):
    data = read_source(root, DATA_REL + '/pre-common-activation-compiler-rule-map.json.txt')
    require(len(data) == 2_609_961 and digest(data) == PRE_ACTIVATION_MAP_SHA256, 'Pre-activation443 SOURCE data drift')
    return data


def require_mapping_parity(current, legacy):
    """All443 semantics stay identical; three exact hash changes plus one exact SOURCE reference."""
    require(isinstance(current, dict) and isinstance(legacy, dict), 'Mapping documents must be objects')
    require(set(current) == set(legacy) | {'mapping_contract'} and 'mapping_contract' not in legacy,
            'Unexpected mapping header keys')
    require(current.get('mapping_contract') == CURRENT_CONTRACT, 'Wrong successor contract')
    require({key: value for key, value in current.items() if key not in ('mapping_contract', 'mappings')} ==
            {key: value for key, value in legacy.items() if key != 'mappings'}, 'Header changed outside contract')
    old = legacy.get('mappings'); new = current.get('mappings')
    require(isinstance(old, list) and len(old) == 443 and isinstance(new, list) and len(new) == 445,
            'Expected complete443 DATA and canonical445 successor')
    require(all(isinstance(row, dict) and set(row) == set(old[0]) for row in old + new),
            'Mapping row shape changed')
    require(len({row['rule_id'] for row in new}) == 445 and len({row['upstream_id'] for row in new}) == 445,
            'Duplicate successor IDs')
    expected_refs = [{'path': path, 'sha256': expected}
                     for path, expected in CURRENT_IMPLEMENTATION_SOURCE_PINS.items()]
    added_path = 'modules/provider-compiler/src/main/kotlin/dev/ktchecker/compiler/CompilerCallObservation.kt'
    original_paths = [ref['path'] for ref in expected_refs if ref['path'] != added_path]
    require(len(expected_refs) == 23 and len(original_paths) == 22, 'Wrong closed observation SOURCE roster')
    for prior, successor in zip(old, new[:443]):
        require({key: value for key, value in prior.items() if key != 'implementation_evidence'} ==
                {key: value for key, value in successor.items() if key != 'implementation_evidence'},
                'Existing semantic mapping changed')
        before = prior['implementation_evidence']; after = successor['implementation_evidence']
        require(len(before) == 22 and len(after) == 23 and len({ref['path'] for ref in before}) == 22,
                'Incomplete or duplicate implementation reference list')
        require([ref['path'] for ref in before] == original_paths and after == expected_refs,
                'Not the exact ordered23 implementation SOURCE references')
        retained = [ref for ref in after if ref['path'] != added_path]
        for a, b in zip(before, retained):
            require(set(a) == set(b) == {'path', 'sha256'} and a['path'] == b['path'], 'Reference path/order changed')
            change = REFERENCE_CHANGES.get(a['path'])
            require((a['sha256'], b['sha256']) == change if change else a == b,
                    'Unreviewed implementation-reference change')
    require(tuple(row['rule_id'] for row in new[-2:]) == NEW_IDS, 'Not exactly the reviewed appended two IDs')
    expected = (
        ('ERROR_SUPPRESSION', 'WARNING', 'probable_defect', 'intent_dependent', 'advisory', 'General syntax',
         'fc0c69ee64fd73fe03de3939aaf68990f78a0baa1f2edac12d02f6d9437a7696'),
        ('INAPPLICABLE_OPERATOR_MODIFIER', 'ERROR', 'correctness', 'high', 'enabled_when_validated', 'Modifiers',
         '1837974c9ea4aa2308ed085b0ccfa0c393547cd5d79459675bcebb796268e4fe'),
    )
    fields = ('upstream_id', 'declared_severity', 'kind', 'certainty', 'default_activation', 'source_section', 'catalog_record_sha256')
    for row, values, line in zip(new[-2:], expected, (197, 428)):
        require(set(row) == set(new[0]) and row['source'] ==
                'https://raw.githubusercontent.com/JetBrains/kotlin/v2.4.21/compiler/fir/checkers/gen/org/jetbrains/kotlin/fir/analysis/diagnostics/FirErrors.kt#L' + str(line),
                'New mapping shape or authoritative declaration changed')
        require(tuple(row[key] for key in fields) == values and row['backend'] == 'common' and row['relation'] == 'exact',
                'New diagnostic contract changed')
        require(row['known_checker_activation_flags'] == [] and row['implementation_evidence'] == new[0]['implementation_evidence'] and
                row['gaps'] == ['Exact mapping availability alone does not establish per-ID fixture testing or tuple qualification'],
                'New diagnostic gained activation flags, changed evidence or coverage credit')
    require('INAPPLICABLE_OPERATOR_MODIFIER_WARNING' not in {row['upstream_id'] for row in new}, 'Warning sibling was aliased')


def current445(root):
    """Affirmative current445 and immutable legacy parity; never return legacy data as current."""
    data = read_source(root, MAP_REL)
    require(digest(data) == CURRENT_MAP_SHA256, 'Not the exact canonical445 source resource')
    current = json.loads(data)
    require_mapping_parity(current, legacy_mapping(root))
    core_data = read_source(root, CORE_REL)
    require(digest(core_data) == CURRENT_CORE_SHA256, 'Not the reviewed797-plus-two planned catalog')
    core = json.loads(core_data)
    legacy_core = json.loads(legacy_source_bytes(root, CORE_REL))
    require(len(legacy_core) == 797 and core[:-2] == legacy_core, 'Existing797 catalog records changed')
    require(len(core) == 799 and tuple(row['id'] for row in core[-2:]) == NEW_IDS and
            all(row['status'] == 'planned' for row in core[-2:]), 'Catalog additions were changed or promoted')
    catalog = {row['id']: row for row in core}
    for row in current['mappings']:
        encoded = json.dumps(catalog[row['rule_id']], sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode()
        require(row['catalog_record_sha256'] == digest(encoded), 'Catalog-link drift')
    actual = current_implementation_references(root)
    require(all(row['implementation_evidence'] == actual for row in current['mappings']), 'Actual implementation bytes drift')
    return current


def current_implementation_references(root):
    """Exact authored23 SOURCE closure; cardinality alone never selects an implementation."""
    main = Path(root).resolve() / 'modules/provider-compiler/src/main/kotlin'
    paths = sorted(path.relative_to(Path(root).resolve()).as_posix() for path in main.rglob('*.kt'))
    require(paths == list(CURRENT_IMPLEMENTATION_SOURCE_PINS), 'Main compiler SOURCE roster changed')
    actual = [{'path': path, 'sha256': digest(read_source(root, path))} for path in paths]
    expected = [{'path': path, 'sha256': value} for path, value in CURRENT_IMPLEMENTATION_SOURCE_PINS.items()]
    require(actual == expected, 'Main compiler SOURCE bytes drift')
    return actual


def require_observation_source_pins(root):
    """Five selected authored SOURCE payloads, never a current-inputs or evidence selector."""
    require(len(OBSERVATION_SOURCE_PINS) == 5, 'Missing closed observation SOURCE pins')
    for relative, pin in OBSERVATION_SOURCE_PINS.items():
        data = read_source(root, relative)
        require((len(data), digest(data)) == (pin['bytes'], pin['sha256']),
                'Observation selected SOURCE drift: ' + relative)
        if relative in OBSERVATION_SOURCE_TRANSITIONS:
            observation_predecessor_bytes(relative, data)


def require_parser_ambiguity_source_pins(root):
    """Two reviewed SOURCE inputs, independent of historical389 and facade5 selectors."""
    require(len(PARSER_AMBIGUITY_SOURCE_PINS) == 2, 'Missing closed parser ambiguity SOURCE pins')
    for relative, pin in PARSER_AMBIGUITY_SOURCE_PINS.items():
        data = read_source(root, relative)
        require((len(data), digest(data)) == (pin['bytes'], pin['sha256']),
                'Parser ambiguity selected SOURCE drift: ' + relative)
        if relative in PARSER_AMBIGUITY_SOURCE_TRANSITIONS:
            parser_ambiguity_predecessor_bytes(relative, data)


def require_current_source_pins(root):
    require(bool(CURRENT_SOURCE_PINS), 'Missing affirmative successor source pins')
    for relative, expected in CURRENT_SOURCE_PINS.items():
        require(digest(read_source(root, relative)) == expected, 'Current445 SOURCE pin mismatch: ' + relative)
    require_observation_source_pins(root)
    require_parser_ambiguity_source_pins(root)


def require_legacy_runner_refusal_source(root):
    runner = read_source(root, 'modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerFixtureRunner.kt')
    before = legacy_source_bytes(root, 'modules/provider-compiler/src/test/kotlin/dev/ktchecker/compiler/CompilerFixtureRunner.kt')
    addition = (b'        // Every existing selector has a legacy443 oracle. Refuse before any path/allocation or child reservation.\n'
                b'        CompilerFixtureMappingContract.requireLegacy(CompilerRuleRegistry.load().mappingIdentity)\n')
    anchor = b'        val suppliedFixtureRoot = Path.of(settings.fixtureDirectory).toAbsolutePath().normalize()\n'
    require(before.count(anchor) == 1 and runner == before.replace(anchor, addition + anchor, 1),
            'Runner changed outside its exact pre-path refusal addition')
    require(runner.index(addition) < runner.index(anchor) < runner.index(b'runRoot = compilerFixtureCreateRunDirectory'),
            'Refusal no longer precedes path resolution and run allocation')
