import { readdirSync, readFileSync } from 'node:fs';
const directory = new URL('../src/app/core/', import.meta.url);
export const apiTransportSource = () => readdirSync(directory).filter(name => name.endsWith('.api.ts')).map(name => readFileSync(new URL(name, directory), 'utf8')).join('\n') + '\n' + readFileSync(new URL('api.service.ts', directory), 'utf8');
