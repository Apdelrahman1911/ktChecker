package fixtures.conflicting_upper_bounds

class Impossible<T> where T : String, T : Number
