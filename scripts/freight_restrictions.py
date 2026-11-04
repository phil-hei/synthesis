"""Conservative, explicit evaluation of selected OSM freight restrictions.

Numeric units and simple weekly time windows are supported. No legal defaults,
permissions, weather, holidays, axle loads or special permits are invented.
"""
import datetime as dt
import math
import re

FIELDS = {'maxheight': ('heightM', 'length'), 'maxwidth': ('widthM', 'length'),
          'maxlength': ('lengthM', 'length'), 'maxweight': ('actualMassKg', 'mass'),
          'maxweightrating': ('ratedGrossMassKg', 'mass'), 'maxaxleload': ('maxAxleLoadKg', 'mass')}
DAY = {name: i for i, name in enumerate(('Mo','Tu','We','Th','Fr','Sa','Su'))}


def resolve_profiles(policy, catalog):
    if policy.get('contract') != 'freight-operating-profiles-v1':
        raise ValueError('unsupported profile contract')
    types = {(row['id'], row['version']): row for row in catalog}
    result = {}
    if set(policy.get('profiles', {})) != {'truck','trailer'}:
        raise ValueError('exactly truck and trailer profiles required')
    for clazz, row in policy['profiles'].items():
        source = types.get((row.get('catalogId'), row.get('catalogVersion')))
        if source is None or source['routing']['sumoVehicleClass'] != clazz:
            raise ValueError('profile catalog reference or class mismatch')
        physical = source['physical']
        resolved = {name: physical[name] for name in ('heightM','widthM','lengthM','curbMassKg','maxSpeedKmh')}
        resolved['ratedGrossMassKg'] = physical['maxGrossMassKg']
        resolved['actualMassKg'] = row.get('actualMassKg')
        for value in resolved.values():
            if type(value) not in (int,float) or not math.isfinite(value) or value <= 0:
                raise ValueError('finite positive physical profile values required')
        if not resolved['curbMassKg'] <= resolved['actualMassKg'] <= resolved['ratedGrossMassKg']:
            raise ValueError('actual mass must lie between curb and rated gross mass')
        if not row.get('loadCase'):
            raise ValueError('explicit load-case provenance required')
        loads = row.get('axleLoadsKg')
        if loads is not None:
            if (not isinstance(loads,list) or not loads or any(type(x) not in (int,float) or not math.isfinite(x) or x <= 0 for x in loads)
                    or abs(sum(loads)-resolved['actualMassKg']) > 1e-6):
                raise ValueError('positive axle loads must sum to actual mass')
        resolved.update(maxAxleLoadKg=max(loads) if loads else None, axleLoadsKg=loads,
                        vehicleClass=clazz, catalogId=source['id'], catalogVersion=source['version'],
                        loadCase=row['loadCase'], catalogProvenance=source['provenance'])
        result[clazz] = resolved
    return result


def quantity(value, kind):
    """Return metres or kg; reject ambiguous lists, localized numbers and defaults."""
    if not isinstance(value,str): return None
    match = re.fullmatch(r'\s*(\d+(?:\.\d+)?)\s*(m|cm|t|kg)?\s*', value)
    if match:
        number, unit = float(match[1]), match[2]
        factors = {'length': {None:1,'m':1,'cm':.01}, 'mass': {None:1000,'t':1000,'kg':1}}
        if unit not in factors[kind] or number <= 0: return None
        return number*factors[kind][unit]
    if kind == 'length':
        match = re.fullmatch(r'\s*(\d+)\s*\'\s*(\d+(?:\.\d+)?)\s*"\s*',value)
        if match and float(match[2]) < 12:
            return float(match[1])*.3048+float(match[2])*.0254
    return None


def condition_at(expression, instant):
    expression = expression.strip()
    if expression.startswith('(') and expression.endswith(')'):
        expression = expression[1:-1].strip()
    match = re.fullmatch(r'(?:(Mo|Tu|We|Th|Fr|Sa|Su)(?:-(Mo|Tu|We|Th|Fr|Sa|Su))?\s+)?(\d{2}):(\d{2})-(\d{2}):(\d{2})',expression)
    if not match: return None
    start_day,end_day,h1,m1,h2,m2 = match.groups()
    h1,m1,h2,m2=map(int,(h1,m1,h2,m2))
    if h1>23 or h2>24 or m1>59 or m2>59 or (h2==24 and m2): return None
    start,end = h1*60+m1,h2*60+m2
    if start == end: return None
    days=set(range(7))
    if start_day:
        begin,finish=DAY[start_day],DAY[end_day or start_day]
        days={begin}
        while begin != finish:
            begin=(begin+1)%7;days.add(begin)
    minute = instant.hour*60+instant.minute+instant.second/60
    if start < end:
        return instant.weekday() in days and start <= minute < end
    return ((instant.weekday() in days and minute >= start)
            or ((instant.weekday()-1)%7 in days and minute < end))


def condition_during(expression, window):
    if window is None: return None
    start,end = window
    if (start.tzinfo is None or end.tzinfo is None or end < start or end-start > dt.timedelta(days=1)
            or start.utcoffset() != end.utcoffset()):
        return None
    # Supported schedules have minute boundaries. Check every boundary and both endpoints.
    moments=[start,end]
    cursor=start.replace(second=0,microsecond=0)+dt.timedelta(minutes=1)
    while cursor < end:
        moments.append(cursor);cursor+=dt.timedelta(minutes=1)
    values={condition_at(expression,t) for t in moments}
    return values.pop() if len(values)==1 else None


def selected_tags(tags, base, clazz, backward):
    relevant = {key:value for key,value in tags.items() if key==base or key.startswith(base+':')}
    chosen = {}
    for key,value in relevant.items():
        tokens=key.split(':')[1:]
        if any(t not in ('forward','backward','hgv','hgv_articulated','physical','conditional','signed') for t in tokens):
            chosen[key] = (value,'unsupported-suffix');continue
        if 'signed' in tokens:
            continue  # Metadata about signage is not a numeric constraint.
        if ('forward' in tokens and backward) or ('backward' in tokens and not backward):continue
        if 'hgv_articulated' in tokens and clazz != 'trailer':continue
        specificity=sum(t in tokens for t in ('forward','backward','hgv','hgv_articulated'))
        group=('physical' in tokens,'conditional' in tokens)
        previous=[k for k,v in chosen.items() if v[1]==group]
        if previous:
            previous_specificity=max(sum(t in k.split(':')[1:] for t in ('forward','backward','hgv','hgv_articulated')) for k in previous)
            if specificity < previous_specificity:continue
            if specificity > previous_specificity:
                for k in previous:del chosen[k]
        chosen[key]=(value,group)
    return chosen


def evaluate_tags(tags, profile, backward=False, window=None):
    events=[]
    def event(key,value,status,reason,**extra):
        events.append({'key':key,'value':value,'status':status,'reason':reason,**extra})
    for base,(field,kind) in FIELDS.items():
        selected=selected_tags(tags,base,profile['vehicleClass'],backward)
        for key,(raw,group) in sorted(selected.items()):
            if group=='unsupported-suffix':
                event(key,raw,'unknown','unsupported-key-suffix');continue
            if not group[1]:
                conditional_values=[value for value,other_group in selected.values()
                                    if isinstance(other_group,tuple) and other_group==(group[0],True)]
                if conditional_values:
                    activations=[condition_during(value.split('@',1)[1],window)
                                 if value.count('@')==1 and ';' not in value else None for value in conditional_values]
                    if None in activations:
                        event(key,raw,'unknown','conditional-override-unresolved');continue
                    if any(activations):
                        event(key,raw,'superseded','active-conditional-override');continue
            value=raw
            if group[1]:
                # Only one explicit rule supported. Multi-rule overrides remain unresolved.
                if raw.count('@')!=1 or ';' in raw:
                    event(key,raw,'unknown','unsupported-conditional-expression');continue
                value,expression=raw.split('@',1)
                active=condition_during(expression,window)
                if active is None:
                    event(key,raw,'unknown','conditional-context-unknown-or-changing');continue
                if not active:
                    event(key,raw,'inactive','condition-inactive-throughout-edge');continue
            limit=quantity(value,kind)
            actual=profile.get(field)
            if limit is None:
                event(key,raw,'unknown','non-numeric-limit-or-unit');continue
            if actual is None:
                event(key,raw,'unknown','vehicle-'+field+'-missing',limit=limit);continue
            event(key,raw,'violation' if actual>limit+1e-9 else 'satisfied','explicit-numeric-comparison',
                  vehicleField=field,vehicleValue=actual,limit=limit,unit='m' if kind=='length' else 'kg')
    # Non-dimensional rules remain separate; speed rules are not access bans.
    for key,value in sorted(tags.items()):
        if key.startswith('maxspeed') and ':conditional' in key:
            event(key,value,'review','speed-rule-not-access-ban')
        elif ':conditional' in key and not any(key==b or key.startswith(b+':') for b in FIELDS):
            event(key,value,'unknown','unsupported-access-conditional')
    direction=':backward' if backward else ':forward'
    base='hgv_articulated' if profile['vehicleClass']=='trailer' and any(k in tags for k in ('hgv_articulated','hgv_articulated'+direction)) else 'hgv'
    hgv_key=base+direction if base+direction in tags else base
    hgv=tags.get(hgv_key)
    conditional_override=any(k.startswith(base+':') and k.endswith(':conditional')
                             and not ((':forward' in k and backward) or (':backward' in k and not backward)) for k in tags)
    if hgv=='no' and conditional_override:event(hgv_key,hgv,'unknown','conditional-access-override-unresolved')
    elif hgv=='no':event(hgv_key,hgv,'violation','explicit-hgv-prohibition')
    elif hgv is not None and hgv not in ('yes','designated'):
        event(hgv_key,hgv,'unknown','purpose-or-exemption-not-confirmed')
    known={'hgv','hgv:forward','hgv:backward','hgv_articulated','hgv_articulated:forward','hgv_articulated:backward'}
    for key,value in tags.items():
        if key.startswith('hgv') and key not in known and ':conditional' not in key:
            event(key,value,'unknown','unsupported-hgv-key')
    for key in ('access','vehicle','motor_vehicle'):
        if tags.get(key) in ('private','destination','delivery','customers','permit','agricultural','forestry','no'):
            event(key,tags[key],'unknown','purpose-or-exemption-not-confirmed')
    return events
