// 임의 자료 2 · 명소마다 짧은 글 (창밖 도슨트 시연용)
// 공모전이 관광지 글을 주지 않아 임의로 씀. 사실(연도 · 숫자)은 넣지 않고 scenario-a.js 의 명소 한 줄 해설과 같은 내용만 짧게.
// 실서비스에서는: 한국관광공사 TourAPI 의 관광지 소개글을 사실 검증(FactGuard) 뒤 미리 문장으로 만들어 둠.
window.SAMPLE_STORIES = {
  // 기능 1 · 지나친 곳 한 줄 (3곳) · 기준: 시나리오 A 에서 경로 옆을 지나가는 명소 셋, 한 문장, 「방금 지난」 으로 시작
  passed: {
    cheonan: '방금 지난 천안삼거리는 능수버들 민요로 알려진 곳이에요.',
    gyeryong: '방금 지난 계룡산은 산줄기가 닭 볏을 쓴 용처럼 생겼대요.',
    naejang: '방금 지난 내장산은 가을 단풍으로 이름난 산이에요.'
  },
  // 기능 3 · 가족 퀴즈 (3문제) · 기준: 지나친 곳 한 줄과 같은 명소 셋, 답은 그 한 줄 해설 안의 낱말, must 는 답에 꼭 들어야 하는 낱말
  quiz: {
    cheonan: { question: '천안삼거리 민요에 나오는 나무는 무엇일까요?', must: ['버들'], right: '정답이에요! 능수버들이에요. 1점!', wrong: '아쉬워요. 정답은 능수버들이에요.' },
    gyeryong: { question: '계룡산 이름에 숨어 있는 동물 두 마리는 뭘까요?', must: ['닭', '용'], right: '정답이에요! 닭 계, 용 룡. 1점!', wrong: '아쉬워요. 정답은 닭과 용이에요.' },
    naejang: { question: '내장산은 어느 계절에 가장 유명할까요?', must: ['가을'], right: '정답이에요! 가을 단풍이에요. 1점!', wrong: '아쉬워요. 정답은 가을이에요.' }
  },
  // 기능 4 · 외국어 승객 해설 (3곳, 영어 · 스페인어) 과 한국어 한마디 (3개)
  // 기준: 지나친 곳 한 줄과 같은 명소 셋을 같은 내용으로 옮김(사실 숫자 없음), 한마디는 여행에서 바로 쓰는 인사 · 감사 · 맛 표현
  foreign: {
    cheonan: { en: 'We are passing Cheonan Samgeori. It is known for an old folk song about weeping willows.',
               es: 'Pasamos por Cheonan Samgeori. Es conocido por una antigua canción popular sobre los sauces llorones.',
               phrase: { ko: '안녕하세요', roman: 'annyeonghaseyo', en: 'hello', es: 'hola' } },
    gyeryong: { en: 'Gyeryongsan is coming up. People say its ridge looks like a dragon wearing a rooster crest.',
                es: 'Se acerca Gyeryongsan. Dicen que su cresta parece un dragón con cresta de gallo.',
                phrase: { ko: '감사합니다', roman: 'gamsahamnida', en: 'thank you', es: 'gracias' } },
    naejang: { en: 'We are near Naejangsan. It is a mountain famous for its autumn leaves.',
               es: 'Estamos cerca de Naejangsan. Es una montaña famosa por sus hojas de otoño.',
               phrase: { ko: '맛있어요', roman: 'masisseoyo', en: 'it is delicious', es: 'está delicioso' } }
  },
  // 기능 5 · 학습 모드 복습 퀴즈 (3곳, 영어 · 스페인어) · 기준: 가족 퀴즈 셋을 공부할 언어로 옮김, 해설은 위 외국어 해설을 그대로 씀
  learn: {
    cheonan: { en: { question: 'What tree is in the Cheonan Samgeori folk song?', must: ['willow'], right: 'Correct! A weeping willow.', wrong: 'Good try. The answer is a weeping willow.' },
               es: { question: '¿Qué árbol aparece en la canción de Cheonan Samgeori?', must: ['sauce'], right: '¡Correcto! Un sauce llorón.', wrong: 'Casi. La respuesta es un sauce llorón.' } },
    gyeryong: { en: { question: 'Which two animals are hidden in the name Gyeryongsan?', must: ['rooster|chicken', 'dragon'], right: 'Correct! A rooster and a dragon.', wrong: 'Good try. The answer is a rooster and a dragon.' },
                es: { question: '¿Qué dos animales se esconden en el nombre Gyeryongsan?', must: ['gallo', 'drag'], right: '¡Correcto! Un gallo y un dragón.', wrong: 'Casi. La respuesta es un gallo y un dragón.' } },
    naejang: { en: { question: 'In which season is Naejangsan most famous?', must: ['autumn|fall'], right: 'Correct! In autumn.', wrong: 'Good try. The answer is autumn.' },
               es: { question: '¿En qué estación es más famoso Naejangsan?', must: ['otoño|otono'], right: '¡Correcto! En otoño.', wrong: 'Casi. La respuesta es otoño.' } }
  },
  // 데이터 2 · 미리 써 둔 해설 문장 (말투 · 길이 · 언어 예 3개) · 기준: scenario-a.js 의 사실 한 줄(계룡산 국립공원 1968년)을
  //   아이 · 어른 · 영어 승객용으로 다르게 씀, 숫자는 사실 자료에 있는 것만. 화면의 사실 검증 단추가 FactGuard 로 검사함(결과: 셋 다 통과)
  // 실서비스에서는: TourAPI 사실 필드로 이런 문장을 출발 전에 미리 만들어 두고 같은 검사를 거쳐 저장
  styles: [
    { who: '아이', text: '계룡산은 1968년에 국립공원이 됐어요. 용이랑 닭이 이름에 숨어 있어요!' },
    { who: '어른', text: '계룡산은 1968년에 국립공원으로 지정된 산으로, 산줄기가 닭 볏을 쓴 용을 닮았다고 전해집니다.' },
    { who: '영어 승객', lang: 'en', text: 'Gyeryongsan became a national park in 1968.' }
  ],
  // 기능 2 · 들러볼 곳 (행사 중인 곳 1곳) · 기준: 기획안 시나리오 A 의 들러볼 곳(담양 죽녹원 대나무축제), 경로에서 몇 분인지는 시연 경로 값
  events: {
    juknok: { what: '대나무축제', away: '3분', text: '지금 담양은 대나무축제 기간이에요. 죽녹원은 가는 길에서 3분이면 들를 수 있어요. 들러 갈까요?' }
  }
};
