// See LICENSE for license details.

//**************************************************************************
// Inspired by the Multi-threaded Matrix Multiply benchmark by C. Celio.
//--------------------------------------------------------------------------
//
// This benchmark multiplies two matrices (2-D arrays) together, or adds them,
// depending on the values in a third vector.

#include <string.h>
#include <stdlib.h>
#include <stdio.h>
#include <stddef.h>
#include "util.h"

// This should typedef data_t for us.
#include "dataset.h"
 
void matrix_multiply(
    const size_t n, const data_t A[], const data_t B[], data_t R[] ) {
  size_t i, j, k;
  for (i = 0; i < n; ++i) {
    for (j = 0; j < n; ++j) {
      int idx = j * n + i;
      int c = 0;
      for (k = 0; k < n; ++k) {
        c += A[j * n + k] * B[k * n + i];
      }
      R[idx] = c;
    }
  }
}


int my_verify(const size_t n, const data_t expected[], const data_t actual[]) {
  size_t i;
  for (i = 0; i < n; ++i)
    if (expected[i] != actual[i])
      return 1;
  return 0;
}

int main(int argc, char **argv) {
   static data_t results_data[ARRAY_SIZE];

   // stats();

	setStats(1);
  matrix_multiply(
       DIM_SIZE, input_a, input_b, results_data);
	setStats(0);

	int res = my_verify(ARRAY_SIZE, input_verify, results_data);

  exit(res);
}
