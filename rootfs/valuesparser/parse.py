#!/usr/bin/env python

import sys
from ruamel.yaml import YAML
from functools import reduce
from argparse import ArgumentParser

# handle arguments
parser = ArgumentParser()
parser.add_argument("--file", dest="filename", metavar="filename.yaml", required=True,
    help="replace in this YAML file")
parser.add_argument("--dry-run", action='store_true',
    dest="dryrun", help="Don't replace in file, just print to stdout")
parser.add_argument("--key-val", dest="kv", metavar="a.b.c=val", required=True,
    help="Key/Value pair to replace in yaml")
args = parser.parse_args()

yaml = YAML()
yaml.explicit_start = False
yaml.indent(mapping=3)
yaml.preserve_quotes = True  # not necessary for your current input

try:
  with open(args.filename) as fp:
    data = yaml.load(fp)
except FileNotFoundError:
  print("File '" + args.filename + "' not found!")
  exit(1)

# get key and value
key = args.kv.split('=')[0]
val = args.kv.split('=')[1]

# get first part of the key dot-path
lookupKey = key.split('.')
lookupKey.pop()

# get last part of key dot-path
itemKey = key.split('.')[-1]
 
# reduce down to last lookupKey dictionary value and assign it to val 
try:
  setItem = reduce(lambda c, k: c[k], lookupKey, data) 
except KeyError:
  print("'" + key + "' is not a valid dot-path in " + args.filename)
  exit(1)

# check if this path exists in yaml
if setItem.get(itemKey, None) == None:
  print("'" + key + "' is not a valid dot-path in " + args.filename)
  exit(1)

setItem[itemKey] = val

ofp = None
if args.dryrun:
    ofp = sys.stdout
else:
    try:
      ofp = open(args.filename, 'w')
    except FileNotFoundError:
      print("File '" + args.filename + "' not found!")
      exit(1)

yaml.dump(data, ofp)


    
