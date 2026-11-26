import torch
from transformers import AutoModelForSequenceClassification, AutoTokenizer

name = "ProsusAI/finbert"
save_dir = "/home/mloayzag/models/finbert"

tok = AutoTokenizer.from_pretrained(name)
# eager attention traces more reliably than sdpa
ref = AutoModelForSequenceClassification.from_pretrained(
    name, attn_implementation="eager"
).eval()

class Wrap(torch.nn.Module):
    def __init__(self, m):
        super().__init__()
        self.m = m
    def forward(self, input_ids, attention_mask):
        return self.m(input_ids=input_ids,
                      attention_mask=attention_mask,
                      return_dict=False)[0]   # logits tensor

enc = tok("Nasdaq futures rise as chipmakers rally after strong earnings.",
          return_tensors="pt")
traced = torch.jit.trace(Wrap(ref).eval(),
                         (enc["input_ids"], enc["attention_mask"]))
traced.save(f"{save_dir}/finbert.pt")

# Sanity check across sequence lengths
for text in ["Up.",
             "Shares fell sharply after the company cut its full-year guidance.",
             "The central bank held rates steady while signaling that further tightening "
             "remains possible if inflation proves stubborn over the coming quarters."]:
    e = tok(text, return_tensors="pt")
    with torch.no_grad():
        a = traced(e["input_ids"], e["attention_mask"])
        b = ref(**e).logits
    print(len(e["input_ids"][0]), torch.allclose(a, b, atol=1e-4))

print(ref.config.id2label)
tok.save_pretrained(save_dir)
