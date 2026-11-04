

package fixtures.val_reassignment

fun broken(): Int { val x = 1; x = 2; return x }
