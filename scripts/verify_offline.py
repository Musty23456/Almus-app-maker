#!/usr/bin/env python3
"""Offline consistency checks for ALMUS APP CREATOR PRO (added in Phase 10).

This is NOT a compiler. It runs where no Android SDK / Gradle / network exists and catches only a narrow class of
mistakes: unbalanced brackets, package/folder mismatches, duplicate top-level types, internal imports that point to
nothing, and workflow files that are not valid YAML. A pass here does not prove the app compiles.
Usage:  python3 scripts/verify_offline.py [project-root]   (exit code 1 when a problem is found)
"""
import re, sys, os, collections
try:
    import yaml
except ImportError:  # PyYAML is optional; without it the workflow files are only checked for existence
    yaml = None
root = sys.argv[1] if len(sys.argv)>1 else "."
src = os.path.join(root, "app/src")
BASE = "com.almus.appcreatorpro"
files = []
for dp, _, fs in os.walk(src):
    for f in fs:
        if f.endswith(".kt"): files.append(os.path.join(dp, f))
def skip_string(code,i):
    """code[i] == '"'. Returns index after the closing quote, honouring escapes and ${...} templates (nested strings too)."""
    n=len(code); j=i+1
    while j<n:
        c=code[j]
        if c=='\\': j+=2; continue
        if c=='"': return j+1
        if c=='$' and j+1<n and code[j+1]=='{':
            depth=1; j+=2
            while j<n and depth:
                if code[j]=='"': j=skip_string(code,j); continue
                if code[j]=='{': depth+=1
                elif code[j]=='}': depth-=1
                j+=1
            continue
        j+=1
    return n
def strip(code):
    out=[];i=0;n=len(code)
    while i<n:
        c=code[i]
        if code.startswith('"""',i):
            j=code.find('"""',i+3); j=n if j<0 else j+3; out.append('""'); i=j
        elif c=='"':
            i=skip_string(code,i); out.append('""')
        elif code.startswith("//",i):
            j=code.find("\n",i); i=n if j<0 else j
        elif code.startswith("/*",i):
            j=code.find("*/",i+2); i=n if j<0 else j+2
        elif c=="'" and i+2<n and (code[i+2]=="'" or (code[i+1]=="\\" and "'" in code[i+2:i+5])):
            j=code.find("'",i+2 if code[i+1]!="\\" else i+3); i=j+1; out.append("0")
        else: out.append(c); i+=1
    return "".join(out)
problems=[]
decls=collections.defaultdict(list)   # fq name -> files
pkg_of={}
for f in files:
    t=open(f,encoding="utf-8").read()
    s=strip(t)
    for a,b in [("(",")"),("[","]"),("{","}")]:
        if s.count(a)!=s.count(b): problems.append(f"UNBALANCED {a}{b} in {os.path.relpath(f,root)} ({s.count(a)} vs {s.count(b)})")
    m=re.search(r"^package\s+([\w.]+)",t,re.M)
    pkg=m.group(1) if m else ""
    pkg_of[f]=pkg
    exp=os.path.relpath(os.path.dirname(f),src).split(os.sep)
    exp=".".join(exp[2:]) if len(exp)>2 else ""
    if pkg!=exp: problems.append(f"PACKAGE/DIR MISMATCH {os.path.relpath(f,root)}: {pkg} vs dir {exp}")
    for m in re.finditer(r"^(?:@\w+(?:\([^)]*\))?\s*)*(?:(?:public|internal|private|data|sealed|enum|abstract|open|annotation|value|inline|fun interface|const)\s+)*(?:class|interface|object|typealias|fun\s+(?:<[^>]+>\s*)?(?:[\w.<>?]+\.)?|val|var)\s+(\w+)",s,re.M):
        if "private" in m.group(0).split(m.group(1))[0]: continue
        decls[(pkg,m.group(1),"main" if "/main/" in f else "test")].append(f)
# duplicate classes/objects (not funs/vals which can overload)
for f in files:
    s=strip(open(f,encoding="utf-8").read())
    for m in re.finditer(r"^(?:(?:public|internal|data|sealed|enum|abstract|open|annotation|value)\s+)*(class|interface|object)\s+(\w+)",s,re.M):
        pass
cls=collections.defaultdict(list)
for f in files:
    s=strip(open(f,encoding="utf-8").read())
    for m in re.finditer(r"^(?:(?:public|internal|data|sealed|enum|abstract|open|annotation|value|fun)\s+)*(class|interface|object)\s+(\w+)",s,re.M):
        cls[(pkg_of[f],m.group(2),"main" if "/main/" in f else "test")].append(os.path.relpath(f,root))
for k,v in cls.items():
    if len(set(v))>1: problems.append(f"DUPLICATE TYPE {k[0]}.{k[1]} in {sorted(set(v))}")
# all declared names per package (any kind) for import resolution
names=collections.defaultdict(set)
for f in files:
    s=strip(open(f,encoding="utf-8").read())
    for m in re.finditer(r"^\s*(?:@\w+\s+)*(?:(?:public|internal|private|protected|data|sealed|enum|abstract|open|annotation|value|inline|const|lateinit|suspend|override|operator|infix|tailrec|external|fun)\s+)*(?:class|interface|object|typealias|fun|val|var)\s+(?:<[^>]+>\s*)?(?:[\w.<>?, *]+\.)?(\w+)",s,re.M):
        names[pkg_of[f]].add(m.group(1))
    # enum entries and nested types: add every capitalised identifier declared via 'class|object|interface' nested too
    for m in re.finditer(r"\b(?:class|object|interface)\s+(\w+)",s): names[pkg_of[f]].add(m.group(1))
    for m in re.finditer(r"^\s{4,}([A-Z][A-Z0-9_]*)\b\s*[,;({]?",s,re.M): names[pkg_of[f]].add(m.group(1))
unres=0
for f in files:
    t=open(f,encoding="utf-8").read()
    for m in re.finditer(r"^import\s+([\w.]+)(?:\.\*)?(?:\s+as\s+\w+)?\s*$",t,re.M):
        imp=m.group(1)
        if not imp.startswith(BASE): continue
        if imp in (BASE+".BuildConfig", BASE+".R"): continue  # generated by AGP at build time
        if m.group(0).strip().endswith(".*"): continue
        parts=imp.split(".")
        # try package = parts[:-k], name = parts[-k]
        ok=False
        for k in range(1,4):
            if len(parts)>k and ".".join(parts[:-k]) in names and parts[-k] in names[".".join(parts[:-k])]:
                ok=True;break
        if not ok:
            unres+=1; problems.append(f"UNRESOLVED INTERNAL IMPORT {imp} in {os.path.relpath(f,root)}")
print("kotlin files:",len(files),"| main:",sum('/main/' in f for f in files),"| test:",sum('/test/' in f for f in files))
print("test methods (@Test):",sum(open(f,encoding='utf-8').read().count("@Test") for f in files if '/test/' in f))
for p in problems: print(p)
print("problems:",len(problems))
# workflows
for wf in sorted(os.listdir(os.path.join(root,".github/workflows"))):
    if yaml is None:
        print('YAML check skipped (PyYAML not installed):',wf); continue
    try:
        d=yaml.safe_load(open(os.path.join(root,".github/workflows",wf)))
        print("YAML ok:",wf,"jobs:",list(d["jobs"].keys()))
    except Exception as e: print("YAML FAIL",wf,e)
sys.exit(1 if problems else 0)
