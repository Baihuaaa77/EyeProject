import torch
import ai_edge_torch
from l2cs import L2CS

model = L2CS(block=ai_edge_torch.resnet.BasicBlock, layers=[3, 4, 6, 3], num_bins=66)