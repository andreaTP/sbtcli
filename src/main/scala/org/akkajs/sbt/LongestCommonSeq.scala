package org.akkajs.sbt
/*
heavily inspired from: https://github.com/vickumar1981/stringdistance
 */

object LongestCommonSeq {
  private def lcs(x: String, y: String, m: Int, n: Int): Int = {
    if (m == 0 || n == 0) 0
    else if (x(m - 1) == y(n - 1)) 1 + lcs(x, y, m - 1, n - 1)
    else math.max(lcs(x, y, m, n - 1), lcs(x, y, m - 1, n))
  }

  def apply(s1: String, s2: String): Int =
    lcs(s1, s2, s1.length, s2.length)
}
