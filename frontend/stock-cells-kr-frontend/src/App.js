// -- (start) bootstrap 
// import './theme/flatly-bootstrap.min.css';
// import './theme/darkly-bootstrap.min.css';
import './theme/litera-bootstrap.min.css';
// import './theme/styles.css';
//-- react-bootstrap-table2
// import PriceCells from './demo/cells/react-bootstrap-table2/PriceCells';
// import 'react-bootstrap-table-next/dist/react-bootstrap-table2.min.css';
// -- (end) bootstrap

// -- (start) component, containers
// import SearchInputComponent from './search/components/SearchInputComponent';
import SearchInputContainer from './search/containers/SearchInputContainer';
import BuyingPlanCell from './planner/buy/BuyingPlanCell';

function App() {
  return (
    <div className='container mt-5'>
      <SearchInputContainer></SearchInputContainer>
      <BuyingPlanCell style={{width: '100%', height: '100%'}}></BuyingPlanCell>
    </div>
  );
}

export default App;
