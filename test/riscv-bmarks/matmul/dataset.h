// THIS FILE WAS AUTOMATICALLY GENERATED WITH generate.py

#ifndef __DATASET_H_ // __DATASET_H_
#define __DATASET_H_ // __DATASET_H_

#define ARRAY_SIZE 25
#define DIM_SIZE 5

typedef unsigned int data_t;

static data_t input_a[25] = {
  6, 6, 9, 7, 7, 
  3, 8, 6, 8, 0, 
  7, 1, 1, 6, 0, 
  4, 2, 3, 6, 0, 
  6, 6, 4, 6, 2
  };

static data_t input_b[25] = {
   9,  3,  5,  8,  6, 
   5,  6,  6,  4,  4, 
   4,  5,  1, 10,  1, 
   5, 10,  0,  9,  4, 
   0, 10,  4,  9,  8
  };

static data_t input_verify[25] = {
  155, 239, 103, 288, 153, 
  131, 167,  69, 188,  88, 
  102,  92,  42, 124,  71, 
   88,  99,  35, 124,  59, 
  130, 154,  78, 184, 104
  };

#endif  // __DATASET_H_
