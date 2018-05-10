#include <stdio.h>
#include "util.h"

#define SIZE 100
typedef int DATATYPE;

__attribute__ ((noinline)) int test(int n, DATATYPE *arr1, DATATYPE *arr2,
  DATATYPE *arr3) {
  int i;
  for (i = 0; i < n; i+=1) {
    if (arr3[i] != arr1[i] + arr2[i])
      return 1;
  }

  return 0;
}

__attribute__ ((noinline)) void vecadd(int n, DATATYPE *arr1, DATATYPE *arr2,
  DATATYPE *arr3) {
  int i;
  for (i = 0; i < n; i+=1) {
    arr3[i] = arr1[i] + arr2[i];
  }
}

int main() {
  DATATYPE arr1[SIZE];
  DATATYPE arr2[SIZE];
  DATATYPE arr3[SIZE];

  int i;
  for (i = 0; i < SIZE; i++) {
    arr1[i] = i;
    arr2[i] = i;
  }

  setStats(1);
  vecadd(SIZE, arr1, arr2, arr3);
  setStats(0);

  return test(SIZE, arr1, arr2, arr3);
}
