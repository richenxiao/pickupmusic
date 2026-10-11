# P0 Spike 阶段 A：读取 ONNX 模型的真实输入/输出形状与元信息。
# 用途：dev-spec §5.1 要求 dim_t / 采样率等参数一律从真实模型读取，不得照抄文档。
# 用法：py -3 tools/karaoke/inspect_onnx.py <model.onnx> [...]
import hashlib
import sys

import onnx


def dim_str(d):
    if d.HasField("dim_value"):
        return str(d.dim_value)
    if d.HasField("dim_param"):
        return f"<{d.dim_param}>"
    return "?"


def value_info_str(v):
    t = v.type.tensor_type
    shape = ", ".join(dim_str(d) for d in t.shape.dim)
    from onnx import TensorProto
    return f"{v.name}: {TensorProto.DataType.Name(t.elem_type)} [{shape}]"


for path in sys.argv[1:]:
    m = onnx.load(path, load_external_data=False)
    print(f"=== {path}")
    print(f"  ir_version={m.ir_version} opset={[ (o.domain or 'ai.onnx', o.version) for o in m.opset_import ]}")
    print(f"  producer={m.producer_name} {m.producer_version}")
    for v in m.graph.input:
        print(f"  input  {value_info_str(v)}")
    for v in m.graph.output:
        print(f"  output {value_info_str(v)}")
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    import os
    print(f"  sizeBytes={os.path.getsize(path)} sha256={h.hexdigest()}")
    ops = {}
    for n in m.graph.node:
        ops[n.op_type] = ops.get(n.op_type, 0) + 1
    print(f"  ops={dict(sorted(ops.items()))}")
