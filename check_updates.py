import re
import urllib.request
import sys
import xml.etree.ElementTree as ET

REPOSITORIES = [
    "https://repo1.maven.org/maven2/",
    "https://jitpack.io/"
]

def parse_toml(file_path):
    versions = {}
    libraries = {}

    with open(file_path, 'r') as f:
        lines = f.readlines()

    section = None
    for line in lines:
        line = line.strip()
        if line.startswith('[') and line.endswith(']'):
            section = line[1:-1]
            continue

        if section == 'versions':
            m = re.match(r'^([a-zA-Z0-9\-\.]+)\s*=\s*"([^"]+)"', line)
            if m:
                versions[m.group(1)] = m.group(2)

        if section == 'libraries':
            m = re.match(r'^([a-zA-Z0-9\-\.]+)\s*=\s*\{\s*module\s*=\s*"([^"]+)"\s*,\s*version\.ref\s*=\s*"([^"]+)"', line)
            if m:
                lib_name = m.group(1)
                module = m.group(2)
                ver_ref = m.group(3)
                if ver_ref in versions:
                    if ver_ref not in libraries:
                        libraries[ver_ref] = []
                    libraries[ver_ref].append(module)

    return versions, libraries

def get_qualifier(version):
    # Matches -alpha, .beta, -rc1, -m2, etc.
    m = re.search(r'(?i)[.\-](alpha|beta|rc|cr|milestone|m\d|eap|snapshot)', version)
    if m:
        q = m.group(1).lower()
        # Normalize m1, m2 to just 'm' if we want to treat them as same qualifier type
        # But usually we want to match exact qualifier string prefix?
        # Actually, 'beta' matches 'beta2'.
        # If I return 'beta', and new is 'beta2', get_qualifier('beta2') returns 'beta'.
        # So they match.
        if q.startswith('m') and len(q) > 1 and q[1].isdigit():
            return 'm'
        return q
    return None

def is_compatible(current_ver, new_ver):
    current_qual = get_qualifier(current_ver)
    new_qual = get_qualifier(new_ver)

    # If current is stable (no qualifier), only accept stable
    if current_qual is None:
        return new_qual is None

    # If current is unstable
    # Accept if new is stable (upgrade to release)
    if new_qual is None:
        return True

    # Accept if qualifiers match (e.g. beta -> beta)
    return current_qual == new_qual

def get_latest_version_from_repo(repo_url, group, artifact, current_ver):
    group_path = group.replace('.', '/')
    url = f"{repo_url}{group_path}/{artifact}/maven-metadata.xml"

    try:
        with urllib.request.urlopen(url, timeout=5) as response:
            xml_content = response.read()
            root = ET.fromstring(xml_content)

            versioning = root.find('versioning')
            if versioning is not None:
                versions = versioning.find('versions')
                if versions is not None:
                    version_list = [v.text for v in versions.findall('version')]

                    # Filter based on compatibility
                    version_list = [v for v in version_list if is_compatible(current_ver, v)]

                    if version_list:
                        return version_list[-1]
    except Exception:
        pass
    return None

def get_latest_version(group, artifact, current_ver):
    for repo in REPOSITORIES:
        ver = get_latest_version_from_repo(repo, group, artifact, current_ver)
        if ver:
            return ver
    return None

def main():
    toml_path = 'gradle/libs.versions.toml'
    current_versions, libraries = parse_toml(toml_path)

    for ver_key, modules in libraries.items():
        if not modules:
            continue

        # Check the first module using this version key
        module = modules[0]
        group, artifact = module.split(':')

        current_ver = current_versions.get(ver_key)
        if not current_ver:
            continue

        latest_ver = get_latest_version(group, artifact, current_ver)

        if latest_ver and latest_ver != current_ver:
            print(f"{ver_key}={latest_ver}")

if __name__ == '__main__':
    main()
