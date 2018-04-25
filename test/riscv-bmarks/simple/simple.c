#include <stdio.h>
#include "util.h"

#define SIZE 100
typedef int DATATYPE;

__attribute__ ((noinline)) int test(DATATYPE *arr1, DATATYPE *arr2,
  int k, int x, int y, int z, int n) {
  int i;
  for (i = x; i < n; i+=k) {
    if (arr2[i] != arr1[i] * y + z)
      return 1;
  }

  return 0;
}

__attribute__ ((noinline)) void simple(DATATYPE *arr1, DATATYPE *arr2,
  int k, int x, int y, int z, int n) {
  int i;
  for (i = x; i < n; i+=k) {
    arr2[i] = arr1[i] * y + z;
  }
}

int main() {
  DATATYPE arr1[SIZE];
  DATATYPE arr2[SIZE];
  int i;
  for (i = 0; i < SIZE; i++)
    arr1[i] = i;

  setStats(1);
  simple(arr1, arr2, 1, 2, 3, 4, SIZE);
  setStats(0);

  return test(arr1, arr2, 1, 2, 3, 4, SIZE);
}
