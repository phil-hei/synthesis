#!/usr/bin/env python3
"""Offline streaming equivalent of the production unconditional OSM HGV transfer.

Only hgv=no and directional overrides are supported. Conditional, dimensional
and purpose-dependent rules are left for explicit review. No simulation starts.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
import xml.sax
from xml.sax.saxutils import XMLGenerator
from xml.sax.xmlreader import AttributesImpl


def digest(path):
    h=hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda:source.read(1024*1024),b''):h.update(block)
    return h.hexdigest()


def rules(osm):
    result={}
    for _,element in ET.iterparse(osm,events=('end',)):
        if element.tag=='way':
            tags={t.get('k'):t.get('v') for t in element.findall('tag')}
            directions={d for d in ('forward','backward') if tags.get('hgv:'+d,tags.get('hgv'))=='no'}
            if directions:result[element.get('id')]=directions
        if element.tag in ('node','way','relation'):element.clear()
    return result


def restrict_lane(attributes):
    values=dict(attributes)
    if 'allow' in values:
        allowed=set(values['allow'].split())
        if 'all' in allowed:raise ValueError('explicit allow=all is unsupported, matching production policy')
        allowed-={'truck','trailer'}
        if allowed:values['allow']=' '.join(sorted(allowed))
        else:
            values.pop('allow')
            values['disallow']='all'
    else:values['disallow']=' '.join(sorted(set(values.get('disallow','').split())|{'truck','trailer'}))
    return values


def apply(osm, network, output):
    prohibited=rules(osm);changed=set()
    class Transfer(XMLGenerator):
        def __init__(self,stream):
            super().__init__(stream,encoding='utf-8',short_empty_elements=True)
            self.edge=None;self.block=False
        def startElement(self,name,attrs):
            if name=='edge':
                self.edge=attrs.get('id')
                match=re.fullmatch(r'-?(\d+)(?:#.*)?',self.edge or '')
                direction='backward' if (self.edge or '').startswith('-') else 'forward'
                self.block=bool(match and direction in prohibited.get(match.group(1),set()))
            if name=='lane' and self.block:
                attrs=AttributesImpl(restrict_lane(attrs))
                changed.add(self.edge)
            super().startElement(name,attrs)
        def endElement(self,name):
            super().endElement(name)
            if name=='edge':self.edge=None;self.block=False
    if output.exists() or output.resolve()==network.resolve():raise ValueError('fresh output file required')
    temporary=output.with_suffix(output.suffix+'.part')
    parser=xml.sax.make_parser()
    parser.setFeature(xml.sax.handler.feature_external_ges,False)
    try:
        with temporary.open('wb') as stream:
            parser.setContentHandler(Transfer(stream))
            parser.parse(str(network))
        temporary.replace(output)
    except Exception:
        temporary.unlink(missing_ok=True)
        raise
    return {'contract':'freight-osm-permissions-pilot-v1','restrictedEdges':sorted(changed),
            'sourceWaysWithProhibitions':len(prohibited),'conditionalAndDimensionRulesSupported':False,
            'hashes':{'osm':digest(osm),'inputNetwork':digest(network),'outputNetwork':digest(output),
                      'script':digest(Path(__file__))}}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('osm','network','output','report'):parser.add_argument('--'+name,required=True,type=Path)
    args=parser.parse_args()
    report=apply(args.osm,args.network,args.output)
    args.report.write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({'restrictedEdges':len(report['restrictedEdges']),'output':str(args.output)}))


if __name__=='__main__':main()
