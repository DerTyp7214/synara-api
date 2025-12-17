package dev.dertyp.data

data class OneOf<O, T>(val one: O? = null, val two: T? = null)