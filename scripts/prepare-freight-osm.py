#!/usr/bin/env python3
"""Local PBF/XML -> explicit OSM site cohort plus complete regional road ways.

Optional research runtime: pyosmium and shapely. No network calls, no simulation
activation. Polygon representatives are not entrances. Relations and ways remain
separate source records for review; no automatic establishment deduplication.
"""
import argparse
from collections import Counter
import hashlib
import importlib.metadata
import json
from pathlib import Path


SHOP_CATEGORIES = {'supermarket': 'grocery_store', 'convenience': 'grocery_store',
                   'grocery': 'grocery_store', 'greengrocer': 'grocery_store',
                   'furniture': 'furniture_store', 'hardware': 'hardware_store',
                   'doityourself': 'building_supply_store', 'building_materials': 'building_supply_store',
                   'trade': 'building_supply_store', 'wholesale': 'wholesaler'}


def category(tags):
    if tags.get('shop') in SHOP_CATEGORIES:
        return SHOP_CATEGORIES[tags['shop']]
    if tags.get('industrial') == 'warehouse' or tags.get('building') == 'warehouse':
        return 'warehouse'
    if tags.get('office') == 'logistics' or tags.get('industrial') == 'logistics':
        return 'freight_and_cargo_service'
    if tags.get('man_made') == 'works' or tags.get('industrial') == 'factory':
        return 'manufacturer'
    return None


def inside(point, bbox):
    return bbox[0] <= point[0] <= bbox[2] and bbox[1] <= point[1] <= bbox[3]


def extract(input_path, bbox, output):
    import osmium
    from shapely import wkb
    from shapely.geometry import LineString, Polygon

    features, failures = [], Counter()
    road_ids, node_ids = set(), set()
    geometry_factory = osmium.geom.WKBFactory()
    road_bbox = [bbox[0]-.02, bbox[1]-.02, bbox[2]+.02, bbox[3]+.02]

    def add(kind, identifier, tags, geometry, method):
        point = list(geometry.coords)[0]
        if not inside(point, bbox):
            return
        features.append({'type':'Feature', 'geometry':{'type':'Point','coordinates':list(point)},
                         'properties':{'id':'osm:{}:{}'.format(kind, identifier), 'name':tags.get('name'),
                                       'category':category(tags), 'positionMethod':method,
                                       'classificationTags':{k:tags[k] for k in ('shop','industrial','building','office','man_made') if k in tags},
                                       'sources':[{'dataset':'OpenStreetMap','license':'ODbL-1.0',
                                                   'record_id':'{}/{}'.format(kind, identifier)}],
                                       'reviewRequired':True, 'truckAccessVerified':False}})

    class Select(osmium.SimpleHandler):
        def node(self, node):
            if category(node.tags) and inside([node.lon,node.lat], bbox):
                from shapely.geometry import Point
                add('node', node.id, dict(node.tags), Point(node.lon,node.lat), 'mapped-node')

        def way(self, way):
            selected_category = category(way.tags)
            if not way.tags.get('highway') and not selected_category:
                return
            try:
                points = [(n.lon,n.lat) for n in way.nodes]
                if len(points)<2:
                    raise ValueError('too few nodes')
            except (ValueError, osmium.InvalidLocationError):
                failures['invalidWayGeometry'] += 1
                return
            xs, ys = zip(*points)
            intersects = max(xs)>=road_bbox[0] and min(xs)<=road_bbox[2] and max(ys)>=road_bbox[1] and min(ys)<=road_bbox[3]
            if way.tags.get('highway') and intersects:
                road_ids.add(way.id)
                node_ids.update(n.ref for n in way.nodes)
            if selected_category:
                if len(points)>=4 and points[0]==points[-1]:
                    polygon = Polygon(points)
                    if not polygon.is_valid:
                        failures['invalidSitePolygon'] += 1
                        return
                    point, method = polygon.representative_point(), 'area-representative-not-entrance'
                else:
                    point, method = LineString(points).interpolate(.5, normalized=True), 'way-midpoint-not-entrance'
                add('way', way.id, dict(way.tags), point, method)

        def area(self, area):
            if area.from_way() or not category(area.tags):
                return
            try:
                polygon = wkb.loads(geometry_factory.create_multipolygon(area), hex=True)
                if not polygon.is_valid:
                    raise ValueError('invalid multipolygon')
                add('relation', area.orig_id(), dict(area.tags), polygon.representative_point(), 'multipolygon-representative-not-entrance')
            except (ValueError, RuntimeError):
                failures['invalidSiteRelation'] += 1

    Select().apply_file(str(input_path), locations=True, idx='flex_mem')
    output.mkdir(parents=True, exist_ok=False)
    writer = osmium.SimpleWriter(str(output/'roads.osm'))
    copied = Counter()
    class WriteRoads(osmium.SimpleHandler):
        def node(self, node):
            if node.id in node_ids:
                writer.add_node(node)
                copied['nodes'] += 1
        def way(self, way):
            if way.id in road_ids:
                writer.add_way(way)
                copied['ways'] += 1
        def relation(self, relation):
            if not relation.tags.get('type','').startswith('restriction'):
                return
            ways = [m.ref for m in relation.members if m.type == 'w']
            nodes = [m.ref for m in relation.members if m.type == 'n']
            if ways and all(w in road_ids for w in ways) and all(n in node_ids for n in nodes):
                writer.add_relation(relation)
                copied['restrictionRelations'] += 1
    try:
        WriteRoads().apply_file(str(input_path))
    finally:
        writer.close()
    features.sort(key=lambda f:f['properties']['id'])
    if len({f['properties']['id'] for f in features}) != len(features):
        raise ValueError('duplicate OSM source identity')
    data = {'type':'FeatureCollection','features':features}
    (output/'sites.geojson').write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n')
    report = {'contract':'freight-osm-extract-v1','bbox':bbox,'roadBbox':road_bbox,
              'siteRecords':len(features),'copiedRoadObjects':dict(copied),'geometryFailures':dict(failures),
              'policy':{'shop':SHOP_CATEGORIES,'warehouse':'industrial=warehouse or building=warehouse',
                        'logistics':'office=logistics or industrial=logistics',
                        'production':'man_made=works or industrial=factory'},
              'scope':'explicit tag cohort, not all OSM businesses; all highway classes in buffered road bbox',
              'versions':{k:importlib.metadata.version(k) for k in ('osmium','shapely')},
              'sourceSha256':hashlib.sha256(input_path.read_bytes()).hexdigest(),
              'scriptSha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              'license':'ODbL-1.0','attribution':'OpenStreetMap contributors, Geofabrik GmbH',
              'simulationReady':False}
    for name in ('sites.geojson','roads.osm'):
        report[name+'Sha256'] = hashlib.sha256((output/name).read_bytes()).hexdigest()
    (output/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input',required=True,type=Path)
    parser.add_argument('--bbox',required=True,nargs=4,type=float)
    parser.add_argument('--output',required=True,type=Path)
    args = parser.parse_args()
    import importlib.util
    spec = importlib.util.spec_from_file_location('site_inspect', Path(__file__).with_name('inspect-freight-sites.py'))
    inspector = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(inspector)
    inspector.validate_bbox(args.bbox)
    if args.output.exists():
        parser.error('fresh output directory required')
    print(json.dumps(extract(args.input,args.bbox,args.output)))


if __name__ == '__main__':
    main()
