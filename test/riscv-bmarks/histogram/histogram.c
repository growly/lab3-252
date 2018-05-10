#include <stdio.h>
#include "util.h"

#define SIZE 100
typedef int DATATYPE;

__attribute__ ((noinline)) int test(DATATYPE *arr1, DATATYPE *arr2, DATATYPE *arr3, int n) {
  int i;
  for (i = 0; i < n; i+=1) {
    arr3[arr1[i]]++;
  }

  for (i = 0; i < n; i+=1) {
    if (arr2[i] != arr3[i])
      return 1;
  }

  return 0;
}

__attribute__ ((noinline)) void histogram(DATATYPE *arr1, DATATYPE *arr2, int n) {
  int i;
  for (i = 0; i < n; i+=1) {
    arr2[arr1[i]]++;
  }
}

int main() {
  DATATYPE arr1[SIZE];
  DATATYPE arr2[SIZE];
  DATATYPE arr3[SIZE];

  int i;
  for (i = 0; i < SIZE; i++)
    arr1[i] = i / 10;

  for (i = 0; i < SIZE; i++)
    arr2[i] = 0;
  for (i = 0; i < SIZE; i++)
    arr3[i] = 0;

  setStats(1);
  histogram(arr1, arr2, SIZE);
  setStats(0);

  return test(arr1, arr2, arr3, SIZE);
}
