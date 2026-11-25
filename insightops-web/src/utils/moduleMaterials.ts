export interface DirectoryEntry { path: string; file: File }
export interface ModuleMaterial extends DirectoryEntry { archivePath: string }

export function localDirectory(files: File[]): DirectoryEntry[] {
  return files.map(file => ({ file, path: file.webkitRelativePath }))
    .filter(({ path }) => path.includes('/') && !path.split('/').some(part => !part || part === '..' || part === '.'
      || part === '.git' || part === 'node_modules' || part === '.idea'))
}

export function moduleRoots(directory: DirectoryEntry[]) {
  return directory.filter(({ path }) => path.endsWith('/pom.xml') && !path.includes('/target/'))
    .map(({ path }) => path.slice(0, -'/pom.xml'.length)).sort()
}

const evidenceNames = ['dependency-tree.txt', 'maven-version.txt', 'application.log']
const javaPath = /^src\/(main|test)\/java\/(?:[A-Za-z0-9_-]+\/)*[A-Za-z0-9_-]+\.java$/
export function scanModule(directory: DirectoryEntry[], module: string) {
  const materials: ModuleMaterial[] = [], tests: string[] = []
  for (const entry of directory) {
    if (!entry.path.startsWith(module + '/')) continue
    const path = entry.path.slice(module.length + 1)
    if (/^target\/surefire-reports\/TEST-[^/]+\.xml$/.test(path)) {
      tests.push(path)
      continue
    }
    const record = evidenceNames.find(name => [name, 'target/' + name, 'evidence/' + name].includes(path))
    const source = path === 'pom.xml' || path === '.mvn/wrapper/maven-wrapper.properties'
      || path === 'src/main/resources/application.properties' || javaPath.test(path)
    if (record || source) materials.push({ ...entry, path, archivePath: record ? 'evidence/' + record : 'sources/' + path })
  }
  return { materials: materials.sort((a, b) => a.path.localeCompare(b.path)), tests: tests.sort() }
}
