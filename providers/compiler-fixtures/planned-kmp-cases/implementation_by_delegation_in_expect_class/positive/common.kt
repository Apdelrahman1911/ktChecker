package fixtures.planned_kmp.implementation_by_delegation_in_expect_class

interface Api { fun read(): Int }
expect class Reader(source: Api) : Api by source
