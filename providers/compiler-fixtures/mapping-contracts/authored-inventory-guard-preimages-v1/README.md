# Authored-inventory guard SOURCE preimages, version 1

These two `.txt` files retain exact current445 SOURCE-control bytes from before
the separately reviewed legacy authoring-inventory generator repair. They are
not legacy443 catalog data, execution receipts, executable modules or a virtual
repository. Never import or execute them. Their old SHA256 values remain in
`test_current445_route.py::LEGACY_SOURCE_PINS`; the actual successor guard files
are checked independently in `SOURCE_GUARD_SUCCESSOR_PINS`.

The generator and type-boundary test preimages are instead reconstructed by two
closed byte inverses in `compiler_mapping_contracts.py`. Each inverse checks an
exact current postimage and the independently frozen complete original bytes.
All original legacy443 mapping/source DATA and all fixture/receipt bytes remain
unchanged. Current catalogs and production mappings remain affirmatively445.
