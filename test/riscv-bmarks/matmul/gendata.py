#!/usr/bin/python3
# 2018-05-07, Arya Reais-Parsi, aryap@berkeley.edu

import random
import sys
import numpy as np

def WriteStartHeaderGuard(filename):
    print('// THIS FILE WAS AUTOMATICALLY GENERATED WITH generate.py\n')
    guard_name = '__' + filename.upper().replace('.', '_') + '_'
    print('#ifndef {} // {}'.format(guard_name, guard_name))
    print('#define {} // {}\n'.format(guard_name, guard_name))

def WriteEndHeaderGuard(filename):
    guard_name = '__' + filename.upper().replace('.', '_') + '_'
    print('#endif  // {}'.format(guard_name))

def WriteDefines(define_dict):
    for k, e in define_dict.items():
        print('#define {} {}'.format(k, e))
    print()

def WriteTypedef(data_type_str):
    print('typedef {} data_t;\n'.format(data_type_str))

def WriteArray(array, name, columns):
    # Print preamble.
    print('static data_t {}[{}] = {{\n  '.format(name, len(array)), end='')

    max_element = max(array)
    max_element_width = len(str(max_element))
    format_str = r'{:>' + str(max_element_width) + '}'

    for i, x in enumerate(array):
        print(format_str.format(x), end='')
        if i != len(array) - 1:
            print(', ', end='')
        if (i + 1) % columns == 0:
            print('\n  ', end='')

    print('};\n')

def Make2D(array, dim):
    m = []
    size = len(array)
    for i in range(dim):
        m.append(array[i*dim:min((i+1)*dim,size)])
    return m

def DoMatMul(a_src, b_src, dim):
    # Need to make sure we mimic 32-bit unsigned arithmetic.
    a = np.uint32(np.matrix(Make2D(a_src, dim)))
    b = np.uint32(np.matrix(Make2D(b_src, dim)))

    r = np.matmul(a, b)

    return np.matrix.tolist(np.matrix.flatten(r))[0]

def main():
    random.seed()

    if len(sys.argv) < 2:
        sys.stderr.write('usage: {} <dim_size>\n'.format(sys.argv[0]))
        exit(1)

    header_name = 'dataset.h'
    dim_size = int(sys.argv[1])
    array_size = dim_size**2
    num_cols = min(dim_size, 8)

    DATA_RAND_MAX = 10 # 2**32 - 1
    COND_RAND_MAX = 10

    define_dict = {
        'ARRAY_SIZE': str(array_size),
        'DIM_SIZE': str(dim_size),
    }

    WriteStartHeaderGuard(header_name)
    WriteDefines(define_dict)
    WriteTypedef('unsigned int')

    a = [random.randint(0, DATA_RAND_MAX) for _ in range(array_size)]
    WriteArray(a, 'input_a', num_cols)

    b = [random.randint(0, DATA_RAND_MAX) for _ in range(array_size)]
    WriteArray(b, 'input_b', num_cols)

    v = DoMatMul(a, b, dim_size)
    WriteArray(v, 'input_verify', num_cols)

    WriteEndHeaderGuard(header_name)

if __name__ == "__main__":
    main()
