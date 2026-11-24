import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

const html = fs.readFileSync(new URL('../app/src/main/assets/index.html', import.meta.url), 'utf8');
const script = html.match(/<script\b[^>]*>([\s\S]*?)<\/script>/i)?.[1] ?? '';
const start = script.indexOf('function fft(');
const end = script.indexOf('// Refine each estimated chord boundary', start);
assert.ok(start >= 0 && end > start, 'Analyzer source anchors missing from index.html');
const notes = script.match(/const NOTES=(\[[^\]]+\])/);
assert.ok(notes, 'Must extract note names from actual app code');
const source = 'const NOTES=' + notes[1] + ';\n' + script.slice(start, end)
  + '\nglobalThis.exportsForTest={fft,pitchChroma,chordEmission,smoothChordFrames,ANALYZER_REV};';
const runtime = vm.createContext({});
vm.runInContext(source, runtime, {filename: 'index.html:analyzer', timeout: 2000});
const analyzer = runtime.exportsForTest;
console.log('ANALYZER_REV=' + analyzer.ANALYZER_REV + ' (extracted from app/src/main/assets/index.html)');

const SAMPLE_RATE = 22050;
const FFT_SIZE = 16384;
const STEP = Math.floor(SAMPLE_RATE * 0.16);
const SEGMENT_SECONDS = 1.8;
const VOICINGS = {
  Am: [45, 57, 60, 64], F: [41, 53, 57, 60], C: [48, 60, 64, 67],
  G: [43, 55, 59, 62], Em: [40, 52, 55, 59], D: [38, 50, 54, 57],
  Dm: [38, 50, 53, 57]
};

function synthesize(sequence) {
  const sampleCount = Math.floor(sequence.length * SEGMENT_SECONDS * SAMPLE_RATE);
  const pcm = new Float32Array(sampleCount);
  for (let step = 0; step < sequence.length; step++) {
    const chord = sequence[step];
    const notes = VOICINGS[chord];
    assert.ok(notes, 'Missing synthetic voicing: ' + chord);
    const startSample = Math.floor(step * SEGMENT_SECONDS * SAMPLE_RATE);
    const segmentSamples = Math.floor(SEGMENT_SECONDS * SAMPLE_RATE);
    for (let i = 0; i < segmentSamples && startSample + i < pcm.length; i++) {
      const seconds = i / SAMPLE_RATE;
      const envelope = Math.min(1, seconds * 10) * Math.min(1, (SEGMENT_SECONDS - seconds) * 8);
      let value = 0;
      for (let k = 0; k < notes.length; k++) {
        const hz = 440 * 2 ** ((notes[k] - 69) / 12);
        const weight = k === 0 ? .55 : .42;
        value += weight * (Math.sin(2 * Math.PI * hz * seconds)
          + .18 * Math.sin(4 * Math.PI * hz * seconds)
          + .06 * Math.sin(6 * Math.PI * hz * seconds));
      }
      pcm[startSample + i] = envelope * value * .33;
    }
  }
  return pcm;
}

function checkSequence(sequence) {
  const pcm = synthesize(sequence);
  const window = Float32Array.from({length: FFT_SIZE},
    (_, i) => .5 - .5 * Math.cos(2 * Math.PI * i / (FFT_SIZE - 1)));
  const real = new Float32Array(FFT_SIZE);
  const imaginary = new Float32Array(FFT_SIZE);
  const magnitude = new Float32Array(FFT_SIZE / 2);
  const emissions = [];
  const amplitudes = [];
  const groundTruth = [];
  const count = Math.floor((pcm.length - FFT_SIZE) / STEP) + 1;
  assert.ok(count > 0);
  for (let frame = 0; frame < count; frame++) {
    const at = frame * STEP;
    let sumSquares = 0;
    for (let i = 0; i < FFT_SIZE; i++) {
      const sample = pcm[at + i];
      sumSquares += sample * sample;
      real[i] = sample * window[i];
      imaginary[i] = 0;
    }
    analyzer.fft(real, imaginary);
    for (let i = 0; i < magnitude.length; i++)
      magnitude[i] = Math.hypot(real[i], imaginary[i]);
    emissions.push(analyzer.chordEmission(analyzer.pitchChroma(
      magnitude, SAMPLE_RATE, FFT_SIZE)));
    amplitudes.push(Math.sqrt(sumSquares / FFT_SIZE));
    const middle = (at + FFT_SIZE / 2) / SAMPLE_RATE;
    const position = middle % SEGMENT_SECONDS;
    groundTruth.push({
      name: sequence[Math.min(sequence.length - 1, Math.floor(middle / SEGMENT_SECONDS))],
      nearChange: Math.min(position, SEGMENT_SECONDS - position) < .26
    });
  }
  const reference = [...amplitudes].sort((a, b) => a - b)[Math.floor(amplitudes.length * .70)] || .001;
  const predicted = analyzer.smoothChordFrames(emissions, amplitudes.map(x => x / reference));
  let checked = 0, correct = 0;
  const mismatches = [];
  for (let i = 0; i < count; i++) {
    if (groundTruth[i].nearChange) continue;
    checked++;
    if (predicted.labels[i] === groundTruth[i].name) correct++;
    else mismatches.push('frame ' + i + ': expected ' + groundTruth[i].name
      + ', got ' + predicted.labels[i]);
  }
  assert.ok(checked >= 20, 'Too few non-boundary frames');
  const accuracy = correct / checked;
  console.log(sequence.join(' → ') + ' ' + correct + '/' + checked
    + ' correct triad frames (' + (100 * accuracy).toFixed(1) + '%)');
  assert.ok(accuracy >= .90, 'Triad recognition regression (<90%): ' + mismatches.join('; '));
}

test('analyzer revision is present in real app code', () => {
  assert.match(analyzer.ANALYZER_REV, /^harmonic-v/);
});
for (const progression of [
  ['Am', 'F', 'C', 'G'],
  ['Em', 'C', 'G', 'D'],
  ['Dm', 'G', 'C', 'Am'],
  ['F', 'G', 'Am', 'Em']
]) test('triad frames: ' + progression.join(' / '), () => checkSequence(progression));
