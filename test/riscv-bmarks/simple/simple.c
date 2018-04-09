#include <stdio.h>
#include "util.h"

#define SIZE 1000
typedef int DATATYPE;

__attribute__ ((noinline)) void simple(DATATYPE *arr1, DATATYPE *arr2, int k) {
  int i;
  for (i = 0; i < SIZE; i+=1) {
    arr2[i] = arr1[i] * k + 1;
  }
}

int main() {
  DATATYPE arr1[SIZE];
  DATATYPE arr2[SIZE];
  int i;
  for (i = 0; i < SIZE; i++)
    arr1[i] = i;

  setStats(1);
  simple(arr1, arr2, 2);
  setStats(0);

  return 0;
}
