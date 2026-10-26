"""Fixed-shape ONNX for LiteRT's GPU: input shape fixed, simplified, and two rewrites the GPU
delegate needs (results unchanged): HardSigmoid as RELU6, attention's 5-D qkv split in 4-D,
and 3-D transposes after a 4-D -> 3-D reshape done in 4-D.

    python -I ocr_tflite.py <in.onnx> <out.onnx> <n,c,h,w>
    onnx2tf -i <out.onnx> -o <dir> -n     # then <dir>/<name>_float32.tflite

Needs Python 3.12 with `onnx2tf[tensorflow]==2.6.9` (it brings onnx and onnxsim). onnx2tf's own
-ois left the shapes dynamic, hence the fixing here. See docs/ocr-experiments.md 4.6.
"""
import sys, onnx, onnxsim
from onnx import helper, numpy_helper
import numpy as np
src, dst, dims = sys.argv[1], sys.argv[2], [int(x) for x in sys.argv[3].split(',')]
m = onnx.load(src)
for d, v in zip(m.graph.input[0].type.tensor_type.shape.dim, dims):
    d.ClearField('dim_param'); d.dim_value = v
m, ok = onnxsim.simplify(m)
assert ok
# HardSigmoid(x) = clip(a*x + b, 0, 1) = Clip(6a*x + 6b, 0, 6) / 6: converts to RELU6, which the GPU runs
nodes, inits, k = [], [], 0
def const(v):
    global k
    k += 1
    name = f'fix_const_{k}'
    inits.append(numpy_helper.from_array(np.array(v, dtype=np.int64 if isinstance(v, list) else np.float32), name))
    return name
for n in m.graph.node:
    if n.op_type != 'HardSigmoid':
        nodes.append(n); continue
    a = next((x.f for x in n.attribute if x.name == 'alpha'), 0.2)
    b = next((x.f for x in n.attribute if x.name == 'beta'), 0.5)
    x, y, p = n.input[0], n.output[0], n.name or f'hs{k}'
    nodes += [
        helper.make_node('Mul', [x, const(6 * a)], [p + '_m'], name=p + '_m'),
        helper.make_node('Add', [p + '_m', const(6 * b)], [p + '_a'], name=p + '_a'),
        helper.make_node('Clip', [p + '_a', const(0.0), const(6.0)], [p + '_c'], name=p + '_c'),
        helper.make_node('Mul', [p + '_c', const(1 / 6)], [y], name=p + '_d'),
    ]
# Attention's qkv split, [B,N,3*H*D] -> Reshape [B,N,3,H,D] -> Transpose(2,0,3,1,4) -> Slice x3 ->
# Squeeze -> q, k, v [B,H,N,D]: the GPU takes at most 4 dims, so slice the last axis first, then
# Reshape [B,N,H,D] and Transpose(0,2,1,3) each part.
init = {i.name: numpy_helper.to_array(i) for i in list(m.graph.initializer)}
consumers = {}
for n in nodes:
    for x in n.input: consumers.setdefault(x, []).append(n)
drop, after = set(), {}
for r in nodes:
    if r.op_type != 'Reshape' or init.get(r.input[1]) is None or len(init[r.input[1]]) != 5: continue
    _, _, three, h, d = init[r.input[1]].tolist()
    t = consumers[r.output[0]][0]
    assert three == 3 and t.op_type == 'Transpose' and list(t.attribute[0].ints) == [2, 0, 3, 1, 4]
    new = []
    for sl in consumers[t.output[0]]:
        assert sl.op_type == 'Slice'
        i = int(init[sl.input[1]][0])
        sq = consumers[sl.output[0]][0]
        assert sq.op_type == 'Squeeze'
        q = f'{sl.name}_qkv'
        new += [
            helper.make_node('Slice', [r.input[0], const([i * h * d]), const([(i + 1) * h * d]), const([2])], [q + '_s'], name=q + '_s'),
            helper.make_node('Reshape', [q + '_s', const([0, -1, h, d])], [q + '_r'], name=q + '_r'),
            helper.make_node('Transpose', [q + '_r'], [sq.output[0]], name=q + '_t', perm=[0, 2, 1, 3]),
        ]
        drop |= {id(sl), id(sq)}
    drop |= {id(r), id(t)}
    after[id(r)] = new
nodes = [x for n in nodes for x in ([] if id(n) in drop else [n]) + after.get(id(n), [])]
# 4-D -> 3-D layout chains (Reshape or Squeeze, then a 3-D Transpose), as on rec's way into and
# out of its attention blocks: the GPU lost that 3-D transpose (its last MatMul saw 120 steps
# instead of 40, all letters wrong). Rewritten as one 4-D Transpose then a Reshape, which the
# GPU handles; each rewrite is checked on an arange before it is made.
if '--keep-3d-transpose' not in sys.argv:
    from itertools import permutations
    from onnx import shape_inference
    g = shape_inference.infer_shapes(helper.make_model(helper.make_graph(nodes, 'g', m.graph.input, m.graph.output, list(m.graph.initializer) + inits), opset_imports=m.opset_import))
    shape = {v.name: [d.dim_value for d in v.type.tensor_type.shape.dim] for v in list(g.graph.value_info) + list(g.graph.input) + list(g.graph.output)}
    users = {}
    for n in nodes:
        for x in n.input: users.setdefault(x, []).append(n)
    out = []
    for n in nodes:
        nxt = users.get(n.output[0], [])
        if (n.op_type in ('Reshape', 'Squeeze') and len(shape.get(n.input[0], [])) == 4 and len(shape.get(n.output[0], [])) == 3
                and len(nxt) == 1 and nxt[0].op_type == 'Transpose'):
            t = nxt[0]
            frm, to = shape[n.input[0]], shape[t.output[0]]
            a = np.arange(int(np.prod(frm))).reshape(frm)
            want = a.reshape(shape[n.output[0]]).transpose(list(t.attribute[0].ints))
            perm = next(p for p in permutations(range(4)) if (a.transpose(p).reshape(to) == want).all())
            print("4-D transpose", frm, list(perm), "->", to)
            tag = n.name + '_4d'
            out += [helper.make_node('Transpose', [n.input[0]], [tag], name=tag, perm=list(perm)),
                    helper.make_node('Reshape', [tag, const(list(to))], [t.output[0]], name=tag + '_r')]
            t.op_type = 'Drop'
            continue
        if n.op_type != 'Drop': out.append(n)
    nodes = [n for n in out if n.op_type != 'Drop']
del m.graph.node[:]
m.graph.node.extend(nodes)
m.graph.initializer.extend(inits)
onnx.checker.check_model(m)
onnx.save(m, dst)
from collections import Counter
print(dst, Counter(n.op_type for n in m.graph.node).most_common())
