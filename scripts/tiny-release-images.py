#!/usr/bin/env python3
"""Bind a tiny Compose release to a verified checkout and locally available immutable application images."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys


def command(*args):
    return subprocess.check_output(list(args), text=True).strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('revision', help='full Git commit of the deployment checkout and all three images')
    parser.add_argument('output', type=Path, help='new compose override file')
    for service in ('server', 'worker', 'web'):
        parser.add_argument('--' + service, required=True, help='locally available image built from the revision')
    args = parser.parse_args()
    repository = Path(__file__).resolve().parents[1]
    if not re.fullmatch(r'[0-9a-f]{40}', args.revision):
        parser.error('a full lowercase Git revision is required')
    if command('git', '-C', str(repository), 'rev-parse', 'HEAD') != args.revision:
        parser.error('the deployment checkout does not match the requested revision')
    if command('git', '-C', str(repository), 'status', '--porcelain', '--untracked-files=no', '--', 'infra', 'scripts'):
        parser.error('tracked deployment scripts or Compose files differ from the selected revision')
    services = {}
    for name, port in (('server', '18080/tcp'), ('worker', '18081/tcp'), ('web', '8080/tcp')):
        reference = getattr(args, name)
        if reference.startswith('-'):
            parser.error('invalid image reference')
        image = json.loads(command('docker', 'image', 'inspect', reference))[0]
        labels = image['Config'].get('Labels') or {}
        if labels.get('org.opencontainers.image.revision') != args.revision:
            parser.error(f'{name} image has a different or missing org.opencontainers.image.revision')
        if port not in (image['Config'].get('ExposedPorts') or {}):
            parser.error(f'{name} image does not declare its expected service port {port}')
        image_id = image['Id']
        if not re.fullmatch(r'sha256:[a-f0-9]{64}', image_id):
            parser.error(f'{name} resolved to an invalid image ID')
        # This file is specific to the Docker host where these IDs were checked.
        services[name] = {'image': image_id, 'pull_policy': 'never'}
    if len({s['image'] for s in services.values()}) != 3:
        parser.error('server, worker and web must be three distinct component images')
    services['uploads-init'] = services['server']
    with args.output.open('x', encoding='utf-8', newline='\n') as f:
        json.dump({'x-insightops-revision':args.revision,'services':services}, f, indent=2)
        f.write('\n')


if __name__ == '__main__':
    try:
        main()
    except (OSError, subprocess.CalledProcessError, ValueError) as err:
        sys.exit(str(err))
